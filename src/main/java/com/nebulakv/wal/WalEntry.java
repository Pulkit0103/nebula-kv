package com.nebulakv.wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * A single record in the Write-Ahead Log.
 *
 * Binary format (big-endian):
 *
 *   [8 bytes: sequence number   ]
 *   [1 byte : operation type    ]  0x01=PUT, 0x02=DELETE
 *   [4 bytes: key length        ]
 *   [N bytes: key (UTF-8)       ]
 *   [4 bytes: value length      ]  0 for DELETE
 *   [M bytes: value (UTF-8)     ]
 *   [4 bytes: CRC32 checksum    ]  covers all preceding bytes in this entry
 *
 * Total minimum size: 8+1+4+4+4 = 21 bytes (key+value empty would be invalid in practice)
 */
public record WalEntry(long sequenceNumber, WalOperation operation, String key, String value) {

    static final byte OP_PUT    = 0x01;
    static final byte OP_DELETE = 0x02;

    // Minimum encoded size: seq(8) + op(1) + keyLen(4) + valLen(4) + checksum(4) = 21
    static final int HEADER_SIZE = 8 + 1 + 4 + 4 + 4;

    public static WalEntry put(long seq, String key, String value) {
        return new WalEntry(seq, WalOperation.PUT, key, value);
    }

    public static WalEntry delete(long seq, String key) {
        return new WalEntry(seq, WalOperation.DELETE, key, "");
    }

    /**
     * Encodes this entry to a ByteBuffer. CRC32 covers all bytes except the checksum field.
     */
    public ByteBuffer encode() {
        byte[] keyBytes   = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);

        int totalSize = HEADER_SIZE + keyBytes.length + valueBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        buf.putLong(sequenceNumber);
        buf.put(operation == WalOperation.PUT ? OP_PUT : OP_DELETE);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);

        // Compute CRC32 over everything written so far.
        int dataEnd = buf.position();
        buf.flip();
        byte[] data = new byte[dataEnd];
        buf.get(data);
        long crc = computeCrc(data);

        // Re-position to append the checksum.
        buf = ByteBuffer.allocate(totalSize);
        buf.put(data);
        buf.putInt((int) crc);
        buf.flip();
        return buf;
    }

    /**
     * Decodes a WalEntry from a ByteBuffer positioned at the start of an entry.
     * Returns null if the buffer has fewer than HEADER_SIZE bytes remaining (truncated tail).
     * Throws WalCorruptionException if the checksum does not match.
     */
    public static WalEntry decode(ByteBuffer buf) {
        int startPos = buf.position();

        if (buf.remaining() < HEADER_SIZE) return null;

        long seq      = buf.getLong();
        byte opByte   = buf.get();
        int keyLen    = buf.getInt();

        if (keyLen <= 0 || buf.remaining() < keyLen + 4) return null;
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);

        int valueLen  = buf.getInt();
        if (valueLen < 0 || buf.remaining() < valueLen + 4) return null;
        byte[] valueBytes = new byte[valueLen];
        buf.get(valueBytes);

        int storedCrc = buf.getInt();

        // Verify checksum over everything before the CRC field.
        int endPos  = buf.position();
        int dataLen = endPos - startPos - 4; // exclude the 4-byte CRC
        buf.position(startPos);
        byte[] data = new byte[dataLen];
        buf.get(data);
        buf.position(endPos);

        long computedCrc = computeCrc(data);
        if ((int) computedCrc != storedCrc) {
            throw new WalCorruptionException(
                "CRC mismatch at seq=" + seq + ": stored=" + Integer.toHexString(storedCrc) +
                " computed=" + Integer.toHexString((int) computedCrc));
        }

        WalOperation op = (opByte == OP_PUT) ? WalOperation.PUT : WalOperation.DELETE;
        String key   = new String(keyBytes, StandardCharsets.UTF_8);
        String value = new String(valueBytes, StandardCharsets.UTF_8);

        return new WalEntry(seq, op, key, value);
    }

    private static long computeCrc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
