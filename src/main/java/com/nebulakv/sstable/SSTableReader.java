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
import java.util.Optional;

/**
 * Reads entries from an immutable SSTable file.
 *
 * Read path:
 *   1. Binary-search the sparse index for the largest key ≤ target key.
 *   2. Seek to that index entry's file offset.
 *   3. Scan forward in the data section until the key is found or passed.
 *
 * This avoids reading the entire file for every lookup (linear scan) while
 * keeping the index small (1 entry per SPARSE_INDEX_INTERVAL data entries).
 */
public final class SSTableReader implements Closeable {

    private final FileChannel channel;
    private final Path path;

    // Loaded once on open.
    private final List<IndexEntry> sparseIndex = new ArrayList<>();
    private final long entryCount;

    public SSTableReader(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        // Read footer to validate and locate the index.
        this.entryCount = readHeader();
        loadSparseIndex();
    }

    /**
     * Looks up a key. Returns the MemTableEntry (may be a tombstone),
     * or empty if the key is not present in this SSTable.
     */
    public Optional<MemTableEntry> get(String targetKey) throws IOException {
        long scanOffset = findScanOffset(targetKey);
        return scanFrom(scanOffset, targetKey);
    }

    /**
     * Returns all entries in sorted key order (for compaction/iteration).
     */
    public List<MemTableEntry> readAllEntries() throws IOException {
        List<MemTableEntry> entries = new ArrayList<>();
        channel.position(24); // skip header
        for (long i = 0; i < entryCount; i++) {
            DataEntry de = readDataEntry();
            if (de == null) break;
            entries.add(de.toMemTableEntry());
        }
        return entries;
    }

    public long entryCount() {
        return entryCount;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private long readHeader() throws IOException {
        channel.position(0);
        ByteBuffer header = ByteBuffer.allocate(24);
        readFully(header);
        int magic   = header.getInt();
        int version = header.getInt();
        long count  = header.getLong();
        // long timestamp = header.getLong(); // not used during read

        if (magic != SSTableWriter.MAGIC) {
            throw new IOException("Invalid SSTable magic: 0x" + Integer.toHexString(magic));
        }
        if (version != SSTableWriter.FORMAT_VERSION) {
            throw new IOException("Unsupported SSTable version: " + version);
        }
        return count;
    }

    private void loadSparseIndex() throws IOException {
        // Locate footer: last 20 bytes of file.
        long fileSize = channel.size();
        channel.position(fileSize - 20);
        ByteBuffer footer = ByteBuffer.allocate(20);
        readFully(footer);

        long indexOffset  = footer.getLong();
        int  indexCount   = footer.getInt();
        // int  crc         = footer.getInt(); // could verify here
        int  footerMagic  = footer.getInt(16);
        if (footerMagic != SSTableWriter.MAGIC) {
            throw new IOException("Invalid SSTable footer magic");
        }

        // Read sparse index.
        channel.position(indexOffset);
        ByteBuffer countBuf = ByteBuffer.allocate(4);
        readFully(countBuf);
        int storedCount = countBuf.getInt();

        for (int i = 0; i < storedCount; i++) {
            ByteBuffer offsetBuf = ByteBuffer.allocate(8);
            readFully(offsetBuf);
            long dataOffset = offsetBuf.getLong();

            ByteBuffer keyLenBuf = ByteBuffer.allocate(4);
            readFully(keyLenBuf);
            int keyLen = keyLenBuf.getInt();

            ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
            readFully(keyBuf);
            String key = new String(keyBuf.array(), StandardCharsets.UTF_8);

            sparseIndex.add(new IndexEntry(dataOffset, key));
        }
    }

    /**
     * Binary searches the sparse index for the largest key ≤ targetKey.
     * Returns the file offset to start scanning from.
     */
    private long findScanOffset(String targetKey) {
        if (sparseIndex.isEmpty()) return 24; // start at data section

        int lo = 0, hi = sparseIndex.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (sparseIndex.get(mid).key.compareTo(targetKey) <= 0) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // If the first index key is already > targetKey, scan from data start.
        if (sparseIndex.get(lo).key.compareTo(targetKey) > 0) return 24;
        return sparseIndex.get(lo).offset;
    }

    private Optional<MemTableEntry> scanFrom(long offset, String targetKey) throws IOException {
        channel.position(offset);
        while (channel.position() < channel.size() - 20) { // -20 for footer
            DataEntry de = readDataEntry();
            if (de == null) break;
            int cmp = de.key.compareTo(targetKey);
            if (cmp == 0) return Optional.of(de.toMemTableEntry());
            if (cmp > 0) break; // passed the target — not present
        }
        return Optional.empty();
    }

    private DataEntry readDataEntry() throws IOException {
        if (channel.position() >= channel.size() - 20) return null;

        ByteBuffer seqBuf = ByteBuffer.allocate(9); // seq(8) + flags(1)
        if (!readFully(seqBuf)) return null;
        long seq  = seqBuf.getLong();
        byte flag = seqBuf.get();

        ByteBuffer keyLenBuf = ByteBuffer.allocate(4);
        readFully(keyLenBuf);
        int keyLen = keyLenBuf.getInt();
        ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
        readFully(keyBuf);
        String key = new String(keyBuf.array(), StandardCharsets.UTF_8);

        ByteBuffer valLenBuf = ByteBuffer.allocate(4);
        readFully(valLenBuf);
        int valLen = valLenBuf.getInt();
        String value = null;
        if (valLen > 0) {
            ByteBuffer valBuf = ByteBuffer.allocate(valLen);
            readFully(valBuf);
            value = new String(valBuf.array(), StandardCharsets.UTF_8);
        }

        boolean tombstone = (flag == 0x01);
        return new DataEntry(seq, key, value, tombstone);
    }

    private boolean readFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n == -1) return false;
        }
        buf.flip();
        return true;
    }

    record IndexEntry(long offset, String key) {}

    record DataEntry(long seq, String key, String value, boolean tombstone) {
        MemTableEntry toMemTableEntry() {
            return tombstone
                ? MemTableEntry.tombstone(seq)
                : MemTableEntry.live(value, seq);
        }
    }
}
