package com.nebulakv.checksum;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Unified checksum utilities for NebulaKV.
 *
 * All persistent structures (WAL, SSTable, Snapshot) use CRC32 checksums.
 * This class centralizes the computation so the algorithm is consistent
 * and the footprint is auditable from a single location.
 *
 * CRC32 vs stronger hashes:
 *   CRC32 is not cryptographic and does not defend against adversarial
 *   corruption. It reliably detects random bit-flip errors (accidental
 *   disk corruption) at very low CPU cost. For data integrity rather than
 *   security, CRC32 is the right trade-off.
 *
 *   If Byzantine fault tolerance is needed (untrusted storage), SHA-256
 *   would be the appropriate upgrade path.
 */
public final class Checksums {

    private Checksums() {}

    /**
     * Computes the CRC32 of the given bytes.
     */
    public static long compute(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Computes the CRC32 of the given ByteBuffer (does not consume it).
     */
    public static long compute(ByteBuffer buf) {
        CRC32 crc = new CRC32();
        ByteBuffer dup = buf.duplicate();
        while (dup.hasRemaining()) {
            crc.update(dup.get());
        }
        return crc.getValue();
    }

    /**
     * Computes the CRC32 of a file region [offset, offset+length).
     *
     * @param path   the file to read
     * @param offset byte offset to start reading from
     * @param length number of bytes to read
     * @return CRC32 value
     * @throws IOException on read failure
     */
    public static long computeFileRegion(Path path, long offset, long length) throws IOException {
        CRC32 crc = new CRC32();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.position(offset);
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(length, 65536));
            long remaining = length;
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(remaining, buf.capacity()));
                int read = ch.read(buf);
                if (read <= 0) break;
                buf.flip();
                while (buf.hasRemaining()) crc.update(buf.get());
                remaining -= read;
            }
        }
        return crc.getValue();
    }

    /**
     * Computes the CRC32 of an entire file.
     *
     * @throws IOException on read failure
     */
    public static long computeFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return computeFileRegion(path, 0, ch.size());
        }
    }

    /**
     * Verifies that the given data matches the expected CRC32.
     *
     * @throws ChecksumMismatchException if the checksum does not match
     */
    public static void verify(byte[] data, long expectedCrc) {
        long actual = compute(data);
        if (actual != expectedCrc) {
            throw new ChecksumMismatchException(expectedCrc, actual);
        }
    }

    /**
     * Verifies that the given ByteBuffer matches the expected CRC32.
     *
     * @throws ChecksumMismatchException if the checksum does not match
     */
    public static void verify(ByteBuffer buf, long expectedCrc) {
        long actual = compute(buf);
        if (actual != expectedCrc) {
            throw new ChecksumMismatchException(expectedCrc, actual);
        }
    }
}
