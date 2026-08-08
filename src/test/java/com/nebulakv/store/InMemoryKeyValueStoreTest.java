package com.nebulakv.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryKeyValueStore — unit tests")
class InMemoryKeyValueStoreTest {

    private InMemoryKeyValueStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKeyValueStore();
    }

    // -------------------------------------------------------------------------
    // PUT + GET
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("put then get returns the stored value")
    void putThenGet() {
        store.put("name", "nebula");
        assertEquals(Optional.of("nebula"), store.get("name"));
    }

    @Test
    @DisplayName("get on missing key returns empty")
    void getMissingKeyReturnsEmpty() {
        assertEquals(Optional.empty(), store.get("ghost"));
    }

    @Test
    @DisplayName("put overwrites an existing key")
    void putOverwritesExistingKey() {
        store.put("env", "dev");
        store.put("env", "prod");
        assertEquals(Optional.of("prod"), store.get("env"));
    }

    @Test
    @DisplayName("overwrite does not change size")
    void overwriteDoesNotChangeSize() {
        store.put("k", "v1");
        store.put("k", "v2");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("put accepts empty string as value")
    void putEmptyStringValue() {
        store.put("empty", "");
        assertEquals(Optional.of(""), store.get("empty"));
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete removes the key")
    void deleteRemovesKey() {
        store.put("temp", "data");
        store.delete("temp");
        assertEquals(Optional.empty(), store.get("temp"));
    }

    @Test
    @DisplayName("delete on non-existent key is a no-op")
    void deleteNonExistentKeyIsNoOp() {
        assertDoesNotThrow(() -> store.delete("phantom"));
    }

    @Test
    @DisplayName("delete decrements size")
    void deleteDecrementSize() {
        store.put("a", "1");
        store.put("b", "2");
        store.delete("a");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("deleting non-existent key does not change size")
    void deleteNonExistentDoesNotChangeSize() {
        store.put("x", "y");
        store.delete("missing");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("put after delete restores the key")
    void putAfterDeleteRestoresKey() {
        store.put("recycled", "v1");
        store.delete("recycled");
        store.put("recycled", "v2");
        assertEquals(Optional.of("v2"), store.get("recycled"));
    }

    // -------------------------------------------------------------------------
    // EXISTS
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exists returns true for a present key")
    void existsReturnsTrueForPresentKey() {
        store.put("present", "yes");
        assertTrue(store.exists("present"));
    }

    @Test
    @DisplayName("exists returns false for a missing key")
    void existsReturnsFalseForMissingKey() {
        assertFalse(store.exists("absent"));
    }

    @Test
    @DisplayName("exists returns false after delete")
    void existsReturnsFalseAfterDelete() {
        store.put("gone", "value");
        store.delete("gone");
        assertFalse(store.exists("gone"));
    }

    // -------------------------------------------------------------------------
    // SIZE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty store has size 0")
    void emptyStoreHasZeroSize() {
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("size tracks additions correctly")
    void sizeTracksAdditions() {
        store.put("a", "1");
        store.put("b", "2");
        store.put("c", "3");
        assertEquals(3, store.size());
    }

    // -------------------------------------------------------------------------
    // INPUT VALIDATION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("put with null key throws IllegalArgumentException")
    void putNullKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.put(null, "v"));
    }

    @Test
    @DisplayName("put with blank key throws IllegalArgumentException")
    void putBlankKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.put("   ", "v"));
    }

    @Test
    @DisplayName("put with null value throws NullPointerException")
    void putNullValueThrows() {
        assertThrows(NullPointerException.class, () -> store.put("k", null));
    }

    @Test
    @DisplayName("get with null key throws IllegalArgumentException")
    void getNullKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.get(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    @DisplayName("get with blank key throws IllegalArgumentException")
    void getBlankKeyThrows(String blank) {
        assertThrows(IllegalArgumentException.class, () -> store.get(blank));
    }

    @Test
    @DisplayName("delete with null key throws IllegalArgumentException")
    void deleteNullKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.delete(null));
    }

    @Test
    @DisplayName("exists with null key throws IllegalArgumentException")
    void existsNullKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.exists(null));
    }

    // -------------------------------------------------------------------------
    // CLEAR
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clear removes all entries and resets size")
    void clearResetsStore() {
        store.put("a", "1");
        store.put("b", "2");
        store.clear();
        assertEquals(0, store.size());
        assertFalse(store.exists("a"));
    }
}
