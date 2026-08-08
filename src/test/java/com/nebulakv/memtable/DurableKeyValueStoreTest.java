package com.nebulakv.memtable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DurableKeyValueStore — WAL-backed crash recovery tests")
class DurableKeyValueStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("put and get work end-to-end")
    void putAndGet() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("lang", "Java");
            assertEquals(Optional.of("Java"), store.get("lang"));
        }
    }

    @Test
    @DisplayName("get on missing key returns empty")
    void getMissingKey() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            assertEquals(Optional.empty(), store.get("nobody"));
        }
    }

    @Test
    @DisplayName("delete marks key as absent")
    void deleteMarksKeyAbsent() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("temp", "data");
            store.delete("temp");
            assertEquals(Optional.empty(), store.get("temp"));
            assertFalse(store.exists("temp"));
        }
    }

    @Test
    @DisplayName("crash recovery: data survives process restart via WAL replay")
    void crashRecovery() throws IOException {
        // First "process": write data and close (simulates normal shutdown or crash after fsync).
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("survivor", "yes");
            store.put("also-alive", "42");
            store.delete("gone");
        }

        // Second "process": open same data directory — WAL is replayed.
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            assertEquals(Optional.of("yes"), store.get("survivor"));
            assertEquals(Optional.of("42"), store.get("also-alive"));
            assertEquals(Optional.empty(), store.get("gone"));
        }
    }

    @Test
    @DisplayName("crash recovery: delete tombstone suppresses earlier put")
    void crashRecoveryTombstoneSuppressesPut() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("key", "old");
            store.delete("key");
        }
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            assertEquals(Optional.empty(), store.get("key"));
            assertFalse(store.exists("key"));
        }
    }

    @Test
    @DisplayName("sequence numbers continue after restart")
    void sequenceContinuesAfterRestart() throws IOException {
        long firstSize;
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("a", "1");
            store.put("b", "2");
            firstSize = store.memTableSizeBytes();
        }
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("c", "3"); // must work without sequence collision
            assertEquals(Optional.of("1"), store.get("a"));
            assertEquals(Optional.of("3"), store.get("c"));
        }
    }

    @Test
    @DisplayName("overwrite is reflected correctly after recovery")
    void overwriteRecovery() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("env", "dev");
            store.put("env", "prod");
        }
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            assertEquals(Optional.of("prod"), store.get("env"));
        }
    }

    @Test
    @DisplayName("size() counts live entries only")
    void sizeCountsLiveEntries() throws IOException {
        try (DurableKeyValueStore store = new DurableKeyValueStore(tempDir)) {
            store.put("a", "1");
            store.put("b", "2");
            store.delete("a");
            assertEquals(1, store.size());
        }
    }
}
