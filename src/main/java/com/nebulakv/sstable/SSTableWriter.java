package com.nebulakv.sstable;

import com.nebulakv.memtable.MemTableEntry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Writes an immutable SSTable file from a sorted MemTable snapshot.
 *
 * File format (big-endian):
 *
 *   HEADER (24 bytes):
 *     [4 bytes: magic number  = 0x4E4B5654 ("NKVT")]
 *     [4 bytes: format version = 1          ]
 *     [8 bytes: entry count               ]
 *     [8 bytes: creation timestamp (ms)   ]
 *
 *   DATA SECTION (variable):
 *     For each entry (sorted by key):
 *       [8 bytes: sequence number ]
 *       [1 byte : flags           ]  0x00=live, 0x01=tombstone
 *       [4 bytes: key length      ]
 *       [N bytes: key (UTF-8)     ]
 *       [4 bytes: value length    ]  0 for tombstones
 *       [M bytes: value (UTF-8)   ]
 *
 *   SPARSE INDEX (variable):
 *     [4 bytes: index entry count]
 *     For each index entry:
 *       [8 bytes: file offset of the data entry ]
 *       [4 bytes: key length                    ]
 *       [N bytes: key (UTF-8)                   ]
 *
 *   FOOTER (20 bytes):
 *     [8 bytes: offset of sparse index start ]
 *     [4 bytes: index entry count            ]
 *     [4 bytes: CRC32 of entire file except this checksum field]
 *     [4 bytes: magic number = 0x4E4B5654   ]
 */
public final class SSTableWriter implements Closeable {

    static final int MAGIC = 0x4E4B5654;
    static final int FORMAT_VERSION = 1;
    static final int SPARSE_INDEX_INTERVAL = 16; // index every 16th key

    private final FileChannel channel;
    private final Path path;

    public SSTableWriter(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    /**
     * Writes all entries from the sorted map and closes the file.
     * Entries must be in ascending key order (guaranteed by ConcurrentSkipListMap).
     */
    public void write(Map<String, MemTableEntry> sortedEntries) throws IOException {
        List<IndexEntry> indexEntries = new ArrayList<>();
        long entryCount = sortedEntries.size();
        int i = 0;

        // --- HEADER ---
        ByteBuffer header = ByteBuffer.allocate(24);
        header.putInt(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putLong(entryCount);
        header.putLong(System.currentTimeMillis());
        header.flip();
        writeAll(header);

        // --- DATA SECTION ---
        for (Map.Entry<String, MemTableEntry> kv : sortedEntries.entrySet()) {
            long offset = channel.position();

            // Record sparse index entry every Nth key.
            if (i % SPARSE_INDEX_INTERVAL == 0) {
                indexEntries.add(new IndexEntry(offset, kv.getKey()));
            }
            i++;

            writeDataEntry(kv.getKey(), kv.getValue());
        }

        // --- SPARSE INDEX ---
        long indexOffset = channel.position();
        ByteBuffer indexCountBuf = ByteBuffer.allocate(4);
        indexCountBuf.putInt(indexEntries.size());
        indexCountBuf.flip();
        writeAll(indexCountBuf);

        for (IndexEntry ie : indexEntries) {
            byte[] keyBytes = ie.key.getBytes(StandardCharsets.UTF_8);
            ByteBuffer idxBuf = ByteBuffer.allocate(8 + 4 + keyBytes.length);
            idxBuf.putLong(ie.offset);
            idxBuf.putInt(keyBytes.length);
            idxBuf.put(keyBytes);
            idxBuf.flip();
            writeAll(idxBuf);
        }

        // --- FOOTER ---
        long fileSize = channel.position();
        // CRC32 over entire file content so far.
        long crc = computeFileCrc(fileSize);

        ByteBuffer footer = ByteBuffer.allocate(20);
        footer.putLong(indexOffset);
        footer.putInt(indexEntries.size());
        footer.putInt((int) crc);
        footer.putInt(MAGIC);
        footer.flip();
        writeAll(footer);

        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void writeDataEntry(String key, MemTableEntry entry) throws IOException {
        byte[] keyBytes   = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (!entry.tombstone() && entry.value() != null)
                ? entry.value().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        byte flags = entry.tombstone() ? (byte) 0x01 : (byte) 0x00;

        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 4 + keyBytes.length + 4 + valueBytes.length);
        buf.putLong(entry.sequenceNumber());
        buf.put(flags);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);
        buf.flip();
        writeAll(buf);
    }

    private void writeAll(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) channel.write(buf);
    }

    private long computeFileCrc(long byteCount) throws IOException {
        channel.position(0);
        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(byteCount, 8192));
        CRC32 crc = new CRC32();
        long remaining = byteCount;
        while (remaining > 0) {
            buf.clear();
            if (buf.capacity() > remaining) buf.limit((int) remaining);
            int n = channel.read(buf);
            if (n <= 0) break;
            buf.flip();
            byte[] bytes = new byte[n];
            buf.get(bytes);
            crc.update(bytes);
            remaining -= n;
        }
        return crc.getValue();
    }

    record IndexEntry(long offset, String key) {}
}
