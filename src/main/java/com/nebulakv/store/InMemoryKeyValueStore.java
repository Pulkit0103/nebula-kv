package com.nebulakv.store;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory key-value store backed by ConcurrentHashMap.
 *
 * Phase 2 implementation: no persistence, no WAL, no SSTable.
 * All data is lost when the process exits. This is intentional — durability
 * is added incrementally in Phases 5-7.
 *
 * Concurrency model:
 *   ConcurrentHashMap guarantees visibility and atomicity at the bucket level.
 *   put/delete/exists are individually atomic. There is no multi-key
 *   transaction support in this phase.
 */
public final class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    // Tracks live entry count without full map scan — decremented on delete.
    private final AtomicLong liveCount = new AtomicLong(0);

    @Override
    public void put(String key, String value) {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");

        // If key was already present, count stays the same.
        // If key is new, increment.
        String previous = store.put(key, value);
        if (previous == null) {
            liveCount.incrementAndGet();
        }
    }

    @Override
    public Optional<String> get(String key) {
        validateKey(key);
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        String removed = store.remove(key);
        if (removed != null) {
            liveCount.decrementAndGet();
        }
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return store.containsKey(key);
    }

    @Override
    public long size() {
        return liveCount.get();
    }

    /**
     * Returns a snapshot of all live keys. Used by rebalancing and anti-entropy scans.
     */
    public Set<String> keySet() {
        return Set.copyOf(store.keySet());
    }

    /**
     * Removes all entries. Primarily useful for tests and future snapshot logic.
     */
    public void clear() {
        store.clear();
        liveCount.set(0);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }
}
