package com.nebulakv.wal;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WriteAheadLog — unit and crash-recovery tests")
class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private Path walPath;

    @BeforeEach
    void setUp() {
        walPath = tempDir.resolve("test.wal");
    }

    // -------------------------------------------------------------------------
    // Append + Replay
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("append PUT and replay recovers the entry")
    void appendPutAndReplay() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("user:1", "Alice");
        }
        List<WalEntry> replayed = replay(walPath);

        assertEquals(1, replayed.size());
        WalEntry e = replayed.get(0);
        assertEquals(WalOperation.PUT, e.operation());
        assertEquals("user:1", e.key());
        assertEquals("Alice", e.value());
    }

    @Test
    @DisplayName("append DELETE and replay recovers the tombstone")
    void appendDeleteAndReplay() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("k", "v");
            wal.appendDelete("k");
        }
        List<WalEntry> replayed = replay(walPath);

        assertEquals(2, replayed.size());
        assertEquals(WalOperation.DELETE, replayed.get(1).operation());
        assertEquals("k", replayed.get(1).key());
    }

    @Test
    @DisplayName("sequence numbers are monotonically increasing")
    void sequenceNumbersAreMonotonic() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            long seq1 = wal.appendPut("a", "1");
            long seq2 = wal.appendPut("b", "2");
            long seq3 = wal.appendDelete("a");

            assertTrue(seq1 < seq2, "seq1 must be less than seq2");
            assertTrue(seq2 < seq3, "seq2 must be less than seq3");
        }
    }

    @Test
    @DisplayName("sequence resumes after reopen")
    void sequenceResumesAfterReopen() throws IOException {
        long lastSeq;
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("x", "1");
            lastSeq = wal.appendPut("y", "2");
        }
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            long nextSeq = wal.appendPut("z", "3");
            assertTrue(nextSeq > lastSeq, "sequence must continue after reopen");
        }
    }

    @Test
    @DisplayName("multiple entries are all replayed in order")
    void multipleEntriesReplayedInOrder() throws IOException {
        int count = 50;
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            for (int i = 0; i < count; i++) {
                wal.appendPut("key:" + i, "val:" + i);
            }
        }
        List<WalEntry> replayed = replay(walPath);

        assertEquals(count, replayed.size());
        for (int i = 0; i < count; i++) {
            assertEquals("key:" + i, replayed.get(i).key());
            assertEquals("val:" + i, replayed.get(i).value());
        }
    }

    // -------------------------------------------------------------------------
    // Crash recovery — truncated tail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("truncated tail entry is silently skipped during replay")
    void truncatedTailIsSkipped() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("safe", "value");
            wal.appendPut("also-safe", "value2");
        }

        // Corrupt the last few bytes to simulate a crash mid-write.
        byte[] bytes = Files.readAllBytes(walPath);
        byte[] truncated = new byte[bytes.length - 5]; // chop off end of last entry
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        Files.write(walPath, truncated);

        List<WalEntry> replayed = replay(walPath);
        // Only the first complete entry should be recovered.
        assertEquals(1, replayed.size());
        assertEquals("safe", replayed.get(0).key());
    }

    @Test
    @DisplayName("corrupt checksum entry stops replay at that point")
    void corruptChecksumStopsReplay() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("entry1", "v1");
            wal.appendPut("entry2", "v2");
            wal.appendPut("entry3", "v3");
        }

        // Flip some bits in the second entry's checksum (last 4 bytes of the entry).
        byte[] bytes = Files.readAllBytes(walPath);
        // Each entry: HEADER_SIZE(21) + keyLen + valueLen bytes
        // entry1: 21 + 6 + 2 = 29 bytes; corrupt bytes 26-29 (checksum of entry2's area)
        // Let's just corrupt byte at position 30 which is in the middle of entry2.
        if (bytes.length > 30) {
            bytes[30] ^= (byte) 0xFF;
        }
        Files.write(walPath, bytes);

        List<WalEntry> replayed = replay(walPath);
        // entry1 was fine; entry2 has corrupt checksum → stop
        assertEquals(1, replayed.size());
        assertEquals("entry1", replayed.get(0).key());
    }

    // -------------------------------------------------------------------------
    // Truncate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("truncate clears the WAL file")
    void truncateClearsFile() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("k", "v");
            wal.truncate();
        }
        assertEquals(0, Files.size(walPath));
    }

    @Test
    @DisplayName("append after truncate works correctly")
    void appendAfterTruncate() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.appendPut("old", "data");
            wal.truncate();
            wal.appendPut("new", "data");
        }
        List<WalEntry> replayed = replay(walPath);
        assertEquals(1, replayed.size());
        assertEquals("new", replayed.get(0).key());
    }

    // -------------------------------------------------------------------------
    // WalEntry codec
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("WalEntry encode/decode round-trip for PUT")
    void walEntryPutRoundTrip() {
        WalEntry original = WalEntry.put(42L, "mykey", "myvalue");
        WalEntry decoded = WalEntry.decode(original.encode());

        assertNotNull(decoded);
        assertEquals(42L, decoded.sequenceNumber());
        assertEquals(WalOperation.PUT, decoded.operation());
        assertEquals("mykey", decoded.key());
        assertEquals("myvalue", decoded.value());
    }

    @Test
    @DisplayName("WalEntry encode/decode round-trip for DELETE")
    void walEntryDeleteRoundTrip() {
        WalEntry original = WalEntry.delete(99L, "gone-key");
        WalEntry decoded = WalEntry.decode(original.encode());

        assertNotNull(decoded);
        assertEquals(WalOperation.DELETE, decoded.operation());
        assertEquals("gone-key", decoded.key());
    }

    @Test
    @DisplayName("Tampered WalEntry throws WalCorruptionException")
    void tamperedEntryThrows() {
        WalEntry original = WalEntry.put(1L, "k", "v");
        java.nio.ByteBuffer buf = original.encode();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        // Flip bits in the value area.
        bytes[bytes.length - 5] ^= (byte) 0xFF;

        java.nio.ByteBuffer tampered = java.nio.ByteBuffer.wrap(bytes);
        assertThrows(WalCorruptionException.class, () -> WalEntry.decode(tampered));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static List<WalEntry> replay(Path path) throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        try (WriteAheadLog wal = new WriteAheadLog(path)) {
            wal.replay(entries::add);
        }
        return entries;
    }
}
