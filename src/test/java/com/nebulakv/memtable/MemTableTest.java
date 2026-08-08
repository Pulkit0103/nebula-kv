package com.nebulakv.memtable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemTable — unit tests")
class MemTableTest {

    private MemTable memTable;

    @BeforeEach
    void setUp() {
        memTable = new MemTable();
    }

    @Test
    @DisplayName("put and get returns live entry")
    void putAndGet() {
        memTable.put("city", "London", 1L);
        Optional<MemTableEntry> e = memTable.get("city");
        assertTrue(e.isPresent());
        assertFalse(e.get().tombstone());
        assertEquals("London", e.get().value());
        assertEquals(1L, e.get().sequenceNumber());
    }

    @Test
    @DisplayName("get missing key returns empty")
    void getMissingKey() {
        assertEquals(Optional.empty(), memTable.get("ghost"));
    }

    @Test
    @DisplayName("delete inserts tombstone entry")
    void deleteInsertsTombstone() {
        memTable.put("k", "v", 1L);
        memTable.delete("k", 2L);

        Optional<MemTableEntry> e = memTable.get("k");
        assertTrue(e.isPresent());
        assertTrue(e.get().tombstone());
        assertEquals(2L, e.get().sequenceNumber());
    }

    @Test
    @DisplayName("isLive returns false for tombstone")
    void isLiveReturnsFalseForTombstone() {
        memTable.put("k", "v", 1L);
        memTable.delete("k", 2L);
        assertFalse(memTable.isLive("k"));
    }

    @Test
    @DisplayName("isLive returns false for absent key")
    void isLiveReturnsFalseForAbsent() {
        assertFalse(memTable.isLive("absent"));
    }

    @Test
    @DisplayName("overwrite updates value and keeps entry count stable")
    void overwriteKeepsEntryCount() {
        memTable.put("k", "v1", 1L);
        memTable.put("k", "v2", 2L);

        assertEquals(1, memTable.entryCount());
        assertEquals("v2", memTable.get("k").orElseThrow().value());
    }

    @Test
    @DisplayName("sizeBytes increases with entries and shrinks on overwrite with smaller value")
    void sizeBytesTracking() {
        memTable.put("key", "value", 1L);
        long sizeAfterFirst = memTable.sizeBytes();
        assertTrue(sizeAfterFirst > 0);

        memTable.put("key", "x", 2L); // shorter value
        assertTrue(memTable.sizeBytes() < sizeAfterFirst);
    }

    @Test
    @DisplayName("shouldFlush returns true when threshold is exceeded")
    void shouldFlushExceedsThreshold() {
        // Use a tiny threshold to trigger flush with one entry.
        MemTable tiny = new MemTable(10);
        tiny.put("longkey", "longvalue", 1L);
        assertTrue(tiny.shouldFlush());
    }

    @Test
    @DisplayName("shouldFlush returns false when below threshold")
    void shouldFlushBelowThreshold() {
        assertFalse(memTable.shouldFlush()); // 64 MB default, nothing written
    }

    @Test
    @DisplayName("sortedEntries returns keys in ascending order")
    void sortedEntriesAreOrdered() {
        memTable.put("banana", "b", 1L);
        memTable.put("apple", "a", 2L);
        memTable.put("cherry", "c", 3L);

        String[] keys = memTable.sortedEntries().keySet().toArray(new String[0]);
        assertArrayEquals(new String[]{"apple", "banana", "cherry"}, keys);
    }
}
