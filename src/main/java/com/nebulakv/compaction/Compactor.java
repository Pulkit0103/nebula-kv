package com.nebulakv.compaction;

import com.nebulakv.memtable.MemTableEntry;
import com.nebulakv.sstable.SSTableReader;
import com.nebulakv.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Merges multiple SSTables into a single, compacted SSTable.
 *
 * Compaction achieves two goals:
 *   1. Remove obsolete versions of the same key (keep highest sequence number).
 *   2. Remove tombstones (deleted keys that no longer have older versions to suppress).
 *
 * Algorithm (k-way merge, similar to merge sort):
 *   - Open all input SSTables
 *   - Use a priority queue keyed by (key ASC, sequenceNumber DESC)
 *   - Drain the queue: for each unique key, keep the entry with the highest seq number
 *   - Skip tombstones that have no older versions (they can be dropped during full compaction)
 *   - Write the merged result to a new SSTable
 *   - Only delete input SSTables after the new file is fully written and synced
 *
 * Safety: If the process crashes after writing the new file but before deleting the inputs,
 * the inputs remain valid and the compaction can be retried. The new file gets discarded
 * (no valid footer → ignored on startup).
 *
 * Tombstone retention:
 *   When performing a full major compaction (all SSTables are inputs), tombstones can be
 *   dropped because there are no older files where the key could survive.
 *   In partial compaction, tombstones must be retained to suppress older files not included.
 */
public final class Compactor {

    private final Path outputDir;

    public Compactor(Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Compacts the given SSTable files into a single output file.
     *
     * @param inputPaths     paths to SSTable files to merge (need not be sorted)
     * @param isMajorCompaction if true, tombstones are dropped from output
     * @return path to the new compacted SSTable
     */
    public Path compact(List<Path> inputPaths, boolean isMajorCompaction) throws IOException {
        if (inputPaths.isEmpty()) throw new IllegalArgumentException("No input SSTables");

        // Phase 1: read and merge all entries.
        TreeMap<String, MemTableEntry> merged = mergeAll(inputPaths);

        // Phase 2: drop tombstones if major compaction.
        if (isMajorCompaction) {
            merged.entrySet().removeIf(e -> e.getValue().tombstone());
        }

        // Phase 3: write new SSTable.
        Path outputPath = outputDir.resolve("compacted-" + System.currentTimeMillis() + ".sst");
        try (SSTableWriter writer = new SSTableWriter(outputPath)) {
            writer.write(merged);
        }

        // Phase 4: delete inputs only after new file is synced.
        for (Path input : inputPaths) {
            Files.deleteIfExists(input);
        }

        return outputPath;
    }

    /**
     * Merges all entries from all input SSTables.
     * For each key, keeps the entry with the highest sequence number.
     */
    private TreeMap<String, MemTableEntry> mergeAll(List<Path> inputPaths) throws IOException {
        // Use TreeMap to maintain sorted key order (required for SSTableWriter).
        TreeMap<String, MemTableEntry> merged = new TreeMap<>();

        for (Path path : inputPaths) {
            try (SSTableReader reader = new SSTableReader(path)) {
                // Read all entries via the sparse-index-based full scan.
                readAllEntries(reader, merged);
            }
        }
        return merged;
    }

    /**
     * Reads all entries from a reader and merges them into the accumulator.
     * Higher sequence number always wins.
     */
    private void readAllEntries(SSTableReader reader, TreeMap<String, MemTableEntry> acc)
            throws IOException {
        // We need to iterate all entries in key order.
        // Use SSTableReader's readAllEntries and pair with their keys by re-reading.
        // To get keys alongside entries we do a full sequential scan.
        fullScan(reader, acc);
    }

    private void fullScan(SSTableReader reader, TreeMap<String, MemTableEntry> acc)
            throws IOException {
        // Delegate to a helper that reads raw (key, entry) pairs.
        List<KeyedEntry> all = readKeyedEntries(reader);
        for (KeyedEntry ke : all) {
            MemTableEntry existing = acc.get(ke.key);
            if (existing == null || ke.entry.sequenceNumber() > existing.sequenceNumber()) {
                acc.put(ke.key, ke.entry);
            }
        }
    }

    /**
     * Reads all (key, MemTableEntry) pairs from the SSTable using its public API.
     * We rebuild a KeyedEntry list by reading the data section sequentially.
     */
    private List<KeyedEntry> readKeyedEntries(SSTableReader reader) throws IOException {
        // We extract entries via a package-level approach: use SSTableReader's
        // get() for every key via full iteration. Since SSTableReader exposes
        // readAllEntries() but not keys, we re-implement a full scan here.
        //
        // For compaction correctness this is acceptable — compaction is a
        // background I/O-bound operation, not latency-sensitive.
        //
        // A future optimization: add Iterator<Map.Entry<String,MemTableEntry>>
        // to SSTableReader (Phase 25 concurrency work).
        return readRawEntries(reader);
    }

    /**
     * Full sequential scan of SSTable data section.
     * Returns entries in sorted key order.
     */
    static List<KeyedEntry> readRawEntries(SSTableReader reader) throws IOException {
        // Access the file directly via the public path and re-open it.
        List<KeyedEntry> result = new ArrayList<>();
        Path path = reader.path();

        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(
                path, java.nio.file.StandardOpenOption.READ)) {

            // Skip header (24 bytes).
            fc.position(24);
            long fileSize = fc.size();
            long footerOffset = fileSize - 20; // 20-byte footer

            while (fc.position() < footerOffset) {
                long savedPos = fc.position();

                // Check if we might be at the sparse index section.
                // We determine this by reading and checking if the next bytes
                // are still a valid data entry (seq + flags).
                if (fc.position() >= footerOffset) break;

                // Read seq(8) + flags(1)
                java.nio.ByteBuffer seqBuf = java.nio.ByteBuffer.allocate(9);
                int n = fc.read(seqBuf);
                if (n < 9) break;
                seqBuf.flip();
                long seq  = seqBuf.getLong();
                byte flag = seqBuf.get();
                if (flag != 0x00 && flag != 0x01) {
                    // No longer in data section — hit the index.
                    break;
                }

                java.nio.ByteBuffer klBuf = java.nio.ByteBuffer.allocate(4);
                if (fc.read(klBuf) < 4) break;
                klBuf.flip();
                int keyLen = klBuf.getInt();
                if (keyLen <= 0 || keyLen > 65536) break; // sanity check

                java.nio.ByteBuffer kBuf = java.nio.ByteBuffer.allocate(keyLen);
                if (!readFull(fc, kBuf)) break;
                String key = new String(kBuf.array(), java.nio.charset.StandardCharsets.UTF_8);

                java.nio.ByteBuffer vlBuf = java.nio.ByteBuffer.allocate(4);
                if (fc.read(vlBuf) < 4) break;
                vlBuf.flip();
                int valLen = vlBuf.getInt();

                String value = null;
                if (valLen > 0) {
                    java.nio.ByteBuffer vBuf = java.nio.ByteBuffer.allocate(valLen);
                    if (!readFull(fc, vBuf)) break;
                    value = new String(vBuf.array(), java.nio.charset.StandardCharsets.UTF_8);
                }

                boolean tombstone = (flag == 0x01);
                MemTableEntry entry = tombstone
                        ? MemTableEntry.tombstone(seq)
                        : MemTableEntry.live(value, seq);
                result.add(new KeyedEntry(key, entry));
            }
        }
        return result;
    }

    private static boolean readFull(java.nio.channels.FileChannel fc,
                                     java.nio.ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = fc.read(buf);
            if (n < 0) return false;
        }
        buf.flip();
        return true;
    }

    record KeyedEntry(String key, MemTableEntry entry) {}
}
