package com.nebulakv.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KeyScanner — prefix and range scans")
class KeyScannerTest {

    private InMemoryKeyValueStore store;
    private KeyScanner scanner;

    @BeforeEach
    void setUp() {
        store   = new InMemoryKeyValueStore();
        scanner = new KeyScanner(store);
        // Seed with mixed keys
        for (String k : List.of("user:1", "user:2", "user:10", "order:1", "order:2", "product:A", "product:B", "z")) {
            store.put(k, "v");
        }
    }

    // ---- prefix scans -------------------------------------------------------

    @Test
    @DisplayName("scanPrefix returns only matching keys sorted")
    void prefixMatch() {
        List<String> result = scanner.scanPrefix("user:");
        assertEquals(List.of("user:1", "user:10", "user:2"), result);
    }

    @Test
    @DisplayName("scanPrefix with empty prefix returns all keys sorted")
    void prefixEmpty() {
        List<String> all = scanner.scanPrefix("");
        assertEquals(8, all.size());
        assertEquals(all, all.stream().sorted().toList());
    }

    @Test
    @DisplayName("scanPrefix with no match returns empty list")
    void prefixNoMatch() {
        assertTrue(scanner.scanPrefix("unknown:").isEmpty());
    }

    @Test
    @DisplayName("scanPrefix result is immutable")
    void prefixImmutable() {
        List<String> result = scanner.scanPrefix("order:");
        assertThrows(UnsupportedOperationException.class, () -> result.add("x"));
    }

    // ---- range scans --------------------------------------------------------

    @Test
    @DisplayName("scanRange returns keys in [from, to)")
    void rangeHalfOpen() {
        // "order:" keys sort before "product:" keys
        List<String> result = scanner.scanRange("order:", "product:");
        assertEquals(List.of("order:1", "order:2"), result);
    }

    @Test
    @DisplayName("scanRange with empty from scans from start")
    void rangeOpenFrom() {
        List<String> result = scanner.scanRange("", "order:2");
        assertTrue(result.contains("order:1"));
        assertFalse(result.contains("order:2")); // exclusive upper
    }

    @Test
    @DisplayName("scanRange with empty to scans to end")
    void rangeOpenTo() {
        List<String> result = scanner.scanRange("user:", "");
        assertTrue(result.contains("user:1"));
        assertTrue(result.contains("z"));
    }

    @Test
    @DisplayName("scanRange both empty returns all keys sorted")
    void rangeBothEmpty() {
        List<String> result = scanner.scanRange("", "");
        assertEquals(8, result.size());
    }

    @Test
    @DisplayName("scanRange from >= to throws")
    void rangeInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> scanner.scanRange("z", "a"));
        assertThrows(IllegalArgumentException.class, () -> scanner.scanRange("same", "same"));
    }

    @Test
    @DisplayName("scanRange result is sorted")
    void rangeSorted() {
        List<String> result = scanner.scanRange("", "");
        assertEquals(result, result.stream().sorted().toList());
    }

    // ---- count --------------------------------------------------------------

    @Test
    @DisplayName("count matches store size")
    void count() {
        assertEquals(8, scanner.count());
        store.delete("z");
        assertEquals(7, scanner.count());
    }

    @Test
    @DisplayName("deleted keys are excluded from prefix scan")
    void deletedKeyExcluded() {
        store.delete("user:2");
        assertFalse(scanner.scanPrefix("user:").contains("user:2"));
    }
}
