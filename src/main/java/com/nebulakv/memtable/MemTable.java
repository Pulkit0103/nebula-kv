package com.nebulakv.memtable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, ordered, mutable buffer for recent writes.
 *
 * Backed by ConcurrentSkipListMap for:
 *   - O(log N) put/get/delete
 *   - Sorted iteration (required for SSTable flush — SSTables must be sorted)
 *   - Thread-safe concurrent reads and writes
 *
 * When sizeBytes() exceeds the flush threshold, the caller should:
 *   1. Mark this MemTable immutable (stop writes)
 *   2. Flush to an SSTable in a background thread
 *   3. Replace with a fresh MemTable
 *
 * Immutability is enforced by the caller (the DurableKeyValueStore in Phase 6).
 * This class does not enforce it internally — it is a value store, not a state machine.
 */
public final class MemTable {

    static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 64L * 1024 * 1024; // 64 MB

    private final ConcurrentSkipListMap<String, MemTableEntry> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sizeBytes = new AtomicLong(0);
    private final AtomicLong entryCount = new AtomicLong(0); // all entries including tombstones
    private final AtomicLong liveCount = new AtomicLong(0);  // live (non-tombstone) entries only
    private final long flushThresholdBytes;

    public MemTable() {
        this(DEFAULT_FLUSH_THRESHOLD_BYTES);
    }

    public MemTable(long flushThresholdBytes) {
        this.flushThresholdBytes = flushThresholdBytes;
    }

    /**
     * Inserts or replaces a live entry.
     */
    public void put(String key, String value, long sequenceNumber) {
        MemTableEntry entry = MemTableEntry.live(value, sequenceNumber);
        MemTableEntry previous = data.put(key, entry);
        updateSizeTracking(key, previous, entry);
    }

    /**
     * Inserts a tombstone for the key (logical delete).
     */
    public void delete(String key, long sequenceNumber) {
        MemTableEntry tombstone = MemTableEntry.tombstone(sequenceNumber);
        MemTableEntry previous = data.put(key, tombstone);
        updateSizeTracking(key, previous, tombstone);
    }

    /**
     * Returns the entry for a key, or empty if the key was never written.
     * Callers must check {@link MemTableEntry#tombstone()} to distinguish
     * a deleted key from an absent key.
     */
    public Optional<MemTableEntry> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    /**
     * Returns true if the key exists and is not a tombstone.
     */
    public boolean isLive(String key) {
        MemTableEntry entry = data.get(key);
        return entry != null && !entry.tombstone();
    }

    public long sizeBytes() {
        return sizeBytes.get();
    }

    public long entryCount() {
        return entryCount.get();
    }

    public boolean shouldFlush() {
        return sizeBytes.get() >= flushThresholdBytes;
    }

    /**
     * Returns an immutable snapshot of all entries in sorted key order.
     * Used by the SSTable flush path.
     */
    public Map<String, MemTableEntry> snapshot() {
        return Map.copyOf(data);
    }

    /**
     * Sorted iteration for SSTable flush. Returns entries in ascending key order.
     */
    public ConcurrentSkipListMap<String, MemTableEntry> sortedEntries() {
        return data;
    }

    // -------------------------------------------------------------------------
    // Size tracking
    // -------------------------------------------------------------------------

    public long liveCount() {
        return liveCount.get();
    }

    private void updateSizeTracking(String key, MemTableEntry previous, MemTableEntry current) {
        int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;

        if (previous == null) {
            sizeBytes.addAndGet(keyBytes + current.estimatedValueBytes());
            entryCount.incrementAndGet();
            if (!current.tombstone()) liveCount.incrementAndGet();
        } else {
            sizeBytes.addAndGet(current.estimatedValueBytes() - previous.estimatedValueBytes());
            // Adjust liveCount based on transition.
            if (!previous.tombstone() && current.tombstone())  liveCount.decrementAndGet();
            if (previous.tombstone()  && !current.tombstone()) liveCount.incrementAndGet();
        }
    }
}
