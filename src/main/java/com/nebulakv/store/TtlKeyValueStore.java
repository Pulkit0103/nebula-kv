package com.nebulakv.store;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KeyValueStore decorator that adds per-key time-to-live (TTL) expiry.
 *
 * Design:
 *   - Wraps any KeyValueStore delegate (typically InMemoryKeyValueStore).
 *   - A separate expiry map tracks absolute deadline (System.currentTimeMillis + ttl_ms).
 *   - A background sweeper runs every SWEEP_INTERVAL_MS and deletes expired keys
 *     from the delegate store.
 *   - get/exists check the deadline eagerly — a key past its deadline is treated
 *     as absent even before the sweeper runs (lazy expiry on read).
 *
 * Thread safety: ConcurrentHashMap for expiry map; all delegate operations are
 * delegated to the underlying store which is already thread-safe.
 */
public final class TtlKeyValueStore implements KeyValueStore, AutoCloseable {

    static final long SWEEP_INTERVAL_MS = 500;

    private final KeyValueStore delegate;
    private final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();
    private final AtomicLong expiredCount = new AtomicLong(0);
    private final ScheduledExecutorService sweeper;

    public TtlKeyValueStore(KeyValueStore delegate) {
        this.delegate = delegate;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nebula-ttl-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Standard put with no expiry. */
    @Override
    public void put(String key, String value) {
        delegate.put(key, value);
        expiryMap.remove(key);
    }

    /** Put with TTL. The key expires after ttlMs milliseconds. */
    public void put(String key, String value, long ttlMs) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        delegate.put(key, value);
        expiryMap.put(key, System.currentTimeMillis() + ttlMs);
    }

    @Override
    public Optional<String> get(String key) {
        if (isExpired(key)) return Optional.empty();
        return delegate.get(key);
    }

    @Override
    public void delete(String key) {
        expiryMap.remove(key);
        delegate.delete(key);
    }

    @Override
    public boolean exists(String key) {
        if (isExpired(key)) return false;
        return delegate.exists(key);
    }

    @Override
    public long size() {
        return delegate.size();
    }

    /**
     * Returns remaining TTL in milliseconds for the key, or -1 if the key
     * has no expiry (persistent), or -2 if the key does not exist / already expired.
     */
    public long ttl(String key) {
        Long deadline = expiryMap.get(key);
        if (deadline == null) {
            return delegate.exists(key) ? -1L : -2L;
        }
        long remaining = deadline - System.currentTimeMillis();
        return remaining > 0 ? remaining : -2L;
    }

    /** Total keys evicted by the background sweeper since startup. */
    public long expiredCount() {
        return expiredCount.get();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }

    // -- internals --

    private boolean isExpired(String key) {
        Long deadline = expiryMap.get(key);
        if (deadline == null) return false;
        if (System.currentTimeMillis() >= deadline) {
            expiryMap.remove(key);
            delegate.delete(key);
            expiredCount.incrementAndGet();
            return true;
        }
        return false;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        expiryMap.forEach((key, deadline) -> {
            if (now >= deadline) {
                expiryMap.remove(key);
                delegate.delete(key);
                expiredCount.incrementAndGet();
            }
        });
    }
}
