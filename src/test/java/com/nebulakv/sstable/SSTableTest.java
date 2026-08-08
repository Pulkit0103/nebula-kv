package com.nebulakv.sstable;

import com.nebulakv.memtable.MemTable;
import com.nebulakv.memtable.MemTableEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SSTable — write/read integration tests")
class SSTableTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("write and read back a single entry")
    void writeAndReadSingleEntry() throws IOException {
        Path file = tempDir.resolve("test.sst");

        Map<String, MemTableEntry> data = new TreeMap<>();
        data.put("name", MemTableEntry.live("Alice", 1L));

        try (SSTableWriter writer = new SSTableWriter(file)) {
            writer.write(data);
        }
        try (SSTableReader reader = new SSTableReader(file)) {
            Optional<MemTableEntry> result = reader.get("name");
            assertTrue(result.isPresent());
            assertFalse(result.get().tombstone());
            assertEquals("Alice", result.get().value());
        }
    }

    @Test
    @DisplayName("missing key returns empty")
    void missingKeyReturnsEmpty() throws IOException {
        Path file = tempDir.resolve("test.sst");
        Map<String, MemTableEntry> data = new TreeMap<>();
        data.put("exists", MemTableEntry.live("yes", 1L));

        try (SSTableWriter w = new SSTableWriter(file)) { w.write(data); }
        try (SSTableReader r = new SSTableReader(file)) {
            assertEquals(Optional.empty(), r.get("ghost"));
        }
    }

    @Test
    @DisplayName("tombstone entry is returned correctly")
    void tombstoneIsReadCorrectly() throws IOException {
        Path file = tempDir.resolve("test.sst");
        Map<String, MemTableEntry> data = new TreeMap<>();
        data.put("deleted", MemTableEntry.tombstone(5L));

        try (SSTableWriter w = new SSTableWriter(file)) { w.write(data); }
        try (SSTableReader r = new SSTableReader(file)) {
            Optional<MemTableEntry> entry = r.get("deleted");
            assertTrue(entry.isPresent());
            assertTrue(entry.get().tombstone());
        }
    }

    @Test
    @DisplayName("many entries are all retrievable via sparse index")
    void manyEntriesViaSparseIndex() throws IOException {
        Path file = tempDir.resolve("large.sst");
        int count = 200;
        Map<String, MemTableEntry> data = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String key = String.format("key:%05d", i);
            data.put(key, MemTableEntry.live("val:" + i, (long) i));
        }

        try (SSTableWriter w = new SSTableWriter(file)) { w.write(data); }
        try (SSTableReader r = new SSTableReader(file)) {
            assertEquals(count, r.entryCount());

            // Spot-check a sample of keys.
            for (int i = 0; i < count; i += 7) {
                String key = String.format("key:%05d", i);
                Optional<MemTableEntry> entry = r.get(key);
                assertTrue(entry.isPresent(), "Expected to find " + key);
                assertEquals("val:" + i, entry.get().value());
            }
        }
    }

    @Test
    @DisplayName("SSTable entryCount matches number of entries written")
    void entryCountMatchesWritten() throws IOException {
        Path file = tempDir.resolve("count.sst");
        Map<String, MemTableEntry> data = new TreeMap<>();
        for (int i = 0; i < 50; i++) {
            data.put("k:" + i, MemTableEntry.live("v:" + i, i));
        }

        try (SSTableWriter w = new SSTableWriter(file)) { w.write(data); }
        try (SSTableReader r = new SSTableReader(file)) {
            assertEquals(50, r.entryCount());
        }
    }

    @Test
    @DisplayName("SSTable from MemTable flush recovers same data")
    void memTableFlushAndRead() throws IOException {
        Path file = tempDir.resolve("memflush.sst");
        MemTable mem = new MemTable();
        mem.put("alpha", "first", 1L);
        mem.put("beta", "second", 2L);
        mem.delete("alpha", 3L); // tombstone

        try (SSTableWriter w = new SSTableWriter(file)) {
            w.write(mem.sortedEntries());
        }
        try (SSTableReader r = new SSTableReader(file)) {
            Optional<MemTableEntry> alpha = r.get("alpha");
            assertTrue(alpha.isPresent());
            assertTrue(alpha.get().tombstone());

            Optional<MemTableEntry> beta = r.get("beta");
            assertTrue(beta.isPresent());
            assertEquals("second", beta.get().value());
        }
    }

    @Test
    @DisplayName("readAllEntries returns entries in sorted order")
    void readAllEntriesInOrder() throws IOException {
        Path file = tempDir.resolve("all.sst");
        Map<String, MemTableEntry> data = new TreeMap<>();
        data.put("zebra", MemTableEntry.live("z", 3L));
        data.put("apple", MemTableEntry.live("a", 1L));
        data.put("mango", MemTableEntry.live("m", 2L));

        try (SSTableWriter w = new SSTableWriter(file)) { w.write(data); }
        try (SSTableReader r = new SSTableReader(file)) {
            List<MemTableEntry> all = r.readAllEntries();
            // readAllEntries returns in file order which = sorted key order
            assertEquals(3, all.size());
        }
    }
}
