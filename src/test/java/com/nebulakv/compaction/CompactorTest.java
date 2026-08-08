package com.nebulakv.compaction;

import com.nebulakv.memtable.MemTableEntry;
import com.nebulakv.sstable.SSTableReader;
import com.nebulakv.sstable.SSTableWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compactor — merge, version resolution, tombstone handling")
class CompactorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("compact two SSTables merges their entries")
    void compactTwoTablesWithDistinctKeys() throws IOException {
        Path sst1 = writeSst("sst1.sst", Map.of(
                "alpha", MemTableEntry.live("a1", 1L),
                "beta",  MemTableEntry.live("b1", 2L)
        ));
        Path sst2 = writeSst("sst2.sst", Map.of(
                "gamma", MemTableEntry.live("g1", 3L),
                "delta", MemTableEntry.live("d1", 4L)
        ));

        Compactor compactor = new Compactor(tempDir);
        Path result = compactor.compact(List.of(sst1, sst2), false);

        try (SSTableReader r = new SSTableReader(result)) {
            assertEquals(Optional.of("a1"), get(r, "alpha"));
            assertEquals(Optional.of("b1"), get(r, "beta"));
            assertEquals(Optional.of("g1"), get(r, "gamma"));
            assertEquals(Optional.of("d1"), get(r, "delta"));
        }
    }

    @Test
    @DisplayName("higher sequence number wins for same key")
    void higherSeqWinsForSameKey() throws IOException {
        Path sst1 = writeSst("old.sst", Map.of("k", MemTableEntry.live("old-value", 1L)));
        Path sst2 = writeSst("new.sst", Map.of("k", MemTableEntry.live("new-value", 5L)));

        Compactor compactor = new Compactor(tempDir);
        Path result = compactor.compact(List.of(sst1, sst2), false);

        try (SSTableReader r = new SSTableReader(result)) {
            assertEquals(Optional.of("new-value"), get(r, "k"));
        }
    }

    @Test
    @DisplayName("tombstone suppresses older version in minor compaction")
    void tombstoneSuppressesOlderVersionMinor() throws IOException {
        Path sst1 = writeSst("old.sst", Map.of("deleted-key", MemTableEntry.live("old", 1L)));
        Path sst2 = writeSst("tomb.sst", Map.of("deleted-key", MemTableEntry.tombstone(10L)));

        Compactor compactor = new Compactor(tempDir);
        Path result = compactor.compact(List.of(sst1, sst2), false);

        try (SSTableReader r = new SSTableReader(result)) {
            Optional<MemTableEntry> entry = r.get("deleted-key");
            // In minor compaction, tombstone is retained.
            assertTrue(entry.isPresent());
            assertTrue(entry.get().tombstone(), "tombstone must be preserved in minor compaction");
        }
    }

    @Test
    @DisplayName("tombstones are dropped in major compaction")
    void tombstonesDroppedInMajorCompaction() throws IOException {
        Path sst1 = writeSst("data.sst", Map.of(
                "alive",   MemTableEntry.live("yes", 1L),
                "deleted", MemTableEntry.tombstone(2L)
        ));

        Compactor compactor = new Compactor(tempDir);
        Path result = compactor.compact(List.of(sst1), true);

        try (SSTableReader r = new SSTableReader(result)) {
            // "alive" must be present.
            Optional<MemTableEntry> alive = r.get("alive");
            assertTrue(alive.isPresent());
            assertFalse(alive.get().tombstone());

            // "deleted" tombstone must be gone.
            assertEquals(Optional.empty(), r.get("deleted"));
        }
    }

    @Test
    @DisplayName("input SSTables are deleted after successful compaction")
    void inputsDeletedAfterCompaction() throws IOException {
        Path sst1 = writeSst("input1.sst", Map.of("k1", MemTableEntry.live("v1", 1L)));
        Path sst2 = writeSst("input2.sst", Map.of("k2", MemTableEntry.live("v2", 2L)));

        Compactor compactor = new Compactor(tempDir);
        compactor.compact(List.of(sst1, sst2), false);

        assertFalse(Files.exists(sst1), "input1.sst must be deleted");
        assertFalse(Files.exists(sst2), "input2.sst must be deleted");
    }

    @Test
    @DisplayName("compacted output has correct entry count")
    void compactedOutputEntryCount() throws IOException {
        int n = 100;
        TreeMap<String, MemTableEntry> data = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            data.put(String.format("key:%04d", i), MemTableEntry.live("v" + i, (long) i));
        }
        Path sst = writeSstFromMap("big.sst", data);

        Compactor compactor = new Compactor(tempDir);
        Path result = compactor.compact(List.of(sst), false);

        try (SSTableReader r = new SSTableReader(result)) {
            assertEquals(n, r.entryCount());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path writeSst(String name, Map<String, MemTableEntry> entries) throws IOException {
        return writeSstFromMap(name, new TreeMap<>(entries));
    }

    private Path writeSstFromMap(String name, TreeMap<String, MemTableEntry> entries)
            throws IOException {
        Path path = tempDir.resolve(name);
        try (SSTableWriter w = new SSTableWriter(path)) {
            w.write(entries);
        }
        return path;
    }

    private Optional<String> get(SSTableReader r, String key) throws IOException {
        Optional<MemTableEntry> entry = r.get(key);
        if (entry.isEmpty() || entry.get().tombstone()) return Optional.empty();
        return Optional.of(entry.get().value());
    }
}
