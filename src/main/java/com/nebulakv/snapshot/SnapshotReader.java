package com.nebulakv.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Reads a snapshot file written by {@link SnapshotWriter} and returns the
 * key-value pairs it contains.
 *
 * Validates the CRC32 checksum after reading all entries. Throws
 * {@link SnapshotCorruptionException} if the checksum does not match.
 */
public final class SnapshotReader {

    private SnapshotReader() {}

    /**
     * Reads the snapshot at {@code path} and returns its contents.
     *
     * @throws SnapshotCorruptionException if the magic, version, or CRC is invalid
     * @throws IOException                 on I/O errors
     */
    public static Map<String, String> read(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            ByteBuffer buf = ByteBuffer.allocate((int) fileSize);
            ch.read(buf);
            buf.flip();

            CRC32 crc = new CRC32();

            // Header (16 bytes)
            int magic   = buf.getInt();
            int version = buf.getInt();
            long count  = buf.getLong();

            if (magic != SnapshotWriter.MAGIC) {
                throw new SnapshotCorruptionException("Invalid magic: " + Integer.toHexString(magic));
            }
            if (version != SnapshotWriter.VERSION) {
                throw new SnapshotCorruptionException("Unknown version: " + version);
            }

            // Compute CRC over everything except the final 4-byte checksum field.
            ByteBuffer bodyForCrc = buf.duplicate();
            bodyForCrc.position(0);
            bodyForCrc.limit((int) fileSize - 4);
            while (bodyForCrc.hasRemaining()) {
                crc.update(bodyForCrc.get());
            }

            // Entries
            Map<String, String> result = new LinkedHashMap<>((int) count);
            for (long i = 0; i < count; i++) {
                int keyLen = buf.getInt();
                byte[] keyBytes = new byte[keyLen];
                buf.get(keyBytes);
                int valLen = buf.getInt();
                byte[] valBytes = new byte[valLen];
                buf.get(valBytes);
                result.put(new String(keyBytes, StandardCharsets.UTF_8),
                           new String(valBytes, StandardCharsets.UTF_8));
            }

            // Checksum
            int storedCrc = buf.getInt();
            if (storedCrc != (int) crc.getValue()) {
                throw new SnapshotCorruptionException(
                        "CRC mismatch: stored=" + Integer.toHexString(storedCrc)
                        + " computed=" + Integer.toHexString((int) crc.getValue()));
            }

            return result;
        }
    }
}
