package com.nebulakv.memtable;

import com.nebulakv.store.KeyValueStore;
import com.nebulakv.wal.WalEntry;
import com.nebulakv.wal.WriteAheadLog;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * KeyValueStore backed by WAL + MemTable.
 *
 * Write path:
 *   1. Append to WAL (fsync)
 *   2. Update MemTable
 *   3. Return to caller
 *
 * Read path:
 *   1. Check MemTable
 *   2. If tombstone → key was deleted → return empty
 *   3. If live value → return it
 *   4. If absent → key not found → return empty
 *      (Phase 7 will add SSTable fallback for keys flushed to disk)
 *
 * Crash recovery:
 *   Constructor replays WAL into the MemTable. Any write that was fsynced
 *   before the crash is recovered.
 *
 * Phase 6 does not yet flush the MemTable to disk. When sizeBytes() exceeds
 * the threshold, shouldFlush() returns true — the flush path will be wired
 * in Phase 7 (SSTables).
 */
public final class DurableKeyValueStore implements KeyValueStore, Closeable {

    private final WriteAheadLog wal;
    private final MemTable memTable;

    public DurableKeyValueStore(Path dataDir) throws IOException {
        Path walPath = dataDir.resolve("nebula.wal");
        this.wal = new WriteAheadLog(walPath);
        this.memTable = new MemTable();

        // Crash recovery: replay WAL into MemTable.
        wal.replay(this::applyWalEntry);
    }

    @Override
    public void put(String key, String value) {
        validateKey(key);
        if (value == null) throw new NullPointerException("value must not be null");
        try {
            long seq = wal.appendPut(key, value);
            memTable.put(key, value, seq);
        } catch (IOException e) {
            throw new StorageException("WAL append failed for PUT key=" + key, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        validateKey(key);
        return memTable.get(key)
                .filter(entry -> !entry.tombstone())
                .map(MemTableEntry::value);
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        try {
            long seq = wal.appendDelete(key);
            memTable.delete(key, seq);
        } catch (IOException e) {
            throw new StorageException("WAL append failed for DELETE key=" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return memTable.isLive(key);
    }

    @Override
    public long size() {
        return memTable.liveCount();
    }

    public boolean shouldFlush() {
        return memTable.shouldFlush();
    }

    public long memTableSizeBytes() {
        return memTable.sizeBytes();
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void applyWalEntry(WalEntry entry) {
        switch (entry.operation()) {
            case PUT    -> memTable.put(entry.key(), entry.value(), entry.sequenceNumber());
            case DELETE -> memTable.delete(entry.key(), entry.sequenceNumber());
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }
}
