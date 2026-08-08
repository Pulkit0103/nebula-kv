package com.nebulakv.snapshot;

import com.nebulakv.store.InMemoryKeyValueStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Writes a point-in-time snapshot of a key-value store to disk.
 *
 * File format:
 *   [4 magic][4 version][8 entryCount]
 *   For each entry:
 *     [4 keyLen][N key bytes][4 valLen][M value bytes]
 *   [4 CRC32 over entire file content before this field]
 *
 * Magic: 0x4E4B5350 ("NKSP" — NebulaKV SNaPshot)
 * Version: 1
 *
 * Crash-safety: the snapshot is written to a .tmp file and atomically renamed
 * on completion. If the process crashes mid-write, the partial .tmp file is
 * discarded and the previous snapshot (if any) is intact.
 */
public final class SnapshotWriter {

    static final int MAGIC   = 0x4E4B5350;
    static final int VERSION = 1;

    private SnapshotWriter() {}

    /**
     * Writes a snapshot of the given store to {@code targetPath}.
     * Uses a .tmp intermediary and atomic rename for crash safety.
     *
     * @param store      source of key-value data
     * @param targetPath final snapshot file path
     * @throws IOException on write failure
     */
    public static void write(InMemoryKeyValueStore store, Path targetPath) throws IOException {
        Path tmpPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        CRC32 crc = new CRC32();

        try (FileChannel ch = FileChannel.open(tmpPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            Set<String> keys = store.keySet();

            // Header
            ByteBuffer header = ByteBuffer.allocate(16);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putLong(keys.size());
            header.flip();
            updateCrc(crc, header.duplicate());
            ch.write(header);

            // Entries
            for (String key : keys) {
                Optional<String> optVal = store.get(key);
                if (optVal.isEmpty()) continue; // key deleted between keySet() and get()

                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                byte[] valBytes = optVal.get().getBytes(StandardCharsets.UTF_8);

                ByteBuffer entry = ByteBuffer.allocate(4 + keyBytes.length + 4 + valBytes.length);
                entry.putInt(keyBytes.length);
                entry.put(keyBytes);
                entry.putInt(valBytes.length);
                entry.put(valBytes);
                entry.flip();
                updateCrc(crc, entry.duplicate());
                ch.write(entry);
            }

            // Footer: 4-byte CRC32
            ByteBuffer footer = ByteBuffer.allocate(4);
            footer.putInt((int) crc.getValue());
            footer.flip();
            ch.write(footer);
            ch.force(true);
        }

        // Atomic rename
        java.nio.file.Files.move(tmpPath, targetPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void updateCrc(CRC32 crc, ByteBuffer buf) {
        while (buf.hasRemaining()) {
            crc.update(buf.get());
        }
    }
}
