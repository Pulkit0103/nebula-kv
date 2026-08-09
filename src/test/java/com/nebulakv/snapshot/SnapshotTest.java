package com.nebulakv.snapshot;

import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Snapshot — write and read round-trip")
class SnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("write and read round-trip preserves all entries")
    void roundTrip() throws IOException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        store.put("alpha", "1");
        store.put("beta", "2");
        store.put("gamma", "3");

        Path snap = tempDir.resolve("store.snap");
        SnapshotWriter.write(store, snap);

        Map<String, String> loaded = SnapshotReader.read(snap);

        assertEquals(3, loaded.size());
        assertEquals("1", loaded.get("alpha"));
        assertEquals("2", loaded.get("beta"));
        assertEquals("3", loaded.get("gamma"));
    }

    @Test
    @DisplayName("empty store produces a valid snapshot")
    void emptyStoreSnapshot() throws IOException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        Path snap = tempDir.resolve("empty.snap");
        SnapshotWriter.write(store, snap);

        Map<String, String> loaded = SnapshotReader.read(snap);
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("corrupted checksum throws SnapshotCorruptionException")
    void corruptedChecksumThrows() throws IOException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        store.put("k", "v");

        Path snap = tempDir.resolve("corrupt.snap");
        SnapshotWriter.write(store, snap);

        // Flip the last byte to corrupt the CRC
        byte[] bytes = Files.readAllBytes(snap);
        bytes[bytes.length - 1] ^= 0xFF;
        Files.write(snap, bytes);

        assertThrows(SnapshotCorruptionException.class, () -> SnapshotReader.read(snap));
    }

    @Test
    @DisplayName("invalid magic throws SnapshotCorruptionException")
    void invalidMagicThrows() throws IOException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        store.put("k", "v");

        Path snap = tempDir.resolve("badmagic.snap");
        SnapshotWriter.write(store, snap);

        // Corrupt the magic bytes (first 4 bytes)
        byte[] bytes = Files.readAllBytes(snap);
        bytes[0] ^= 0xFF;
        Files.write(snap, bytes);

        assertThrows(SnapshotCorruptionException.class, () -> SnapshotReader.read(snap));
    }

    @Test
    @DisplayName("crash-safe: .tmp file does not exist after successful write")
    void tmpFileCleanedUp() throws IOException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        store.put("x", "y");

        Path snap = tempDir.resolve("safe.snap");
        SnapshotWriter.write(store, snap);

        assertFalse(Files.exists(snap.resolveSibling("safe.snap.tmp")),
                ".tmp file should be removed after atomic rename");
        assertTrue(Files.exists(snap));
    }
}
