package com.nebulakv.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Append-only Write-Ahead Log using FileChannel + fsync.
 *
 * Durability guarantee:
 *   append(entry) → fsync to disk → return
 *
 * The caller must not ACK a write to the client until append() returns.
 * This ensures that no acknowledged write is ever lost on crash.
 *
 * Crash recovery:
 *   replay(consumer) reads all valid entries from the WAL file sequentially.
 *   A corrupted entry at the tail (partial write at crash boundary) stops replay —
 *   we do NOT throw; we stop at the last valid entry. This is correct because the
 *   last partial entry was never ACKed to the client.
 *
 * Thread safety:
 *   append() is synchronized — only one writer at a time. This matches the
 *   MemTable's single-writer contract. Phase 25 can explore lock-free batching.
 */
public final class WriteAheadLog implements Closeable {

    private final Path walPath;
    private final FileChannel channel;
    private final AtomicLong nextSequence;

    /**
     * Opens (or creates) the WAL file at the given path.
     * The sequence counter starts at 1 + the highest sequence number found during replay,
     * so after recovery the sequence continues from where it left off.
     */
    public WriteAheadLog(Path walPath) throws IOException {
        this.walPath = walPath;
        Files.createDirectories(walPath.getParent());
        this.channel = FileChannel.open(walPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        // Scan existing entries to determine the next sequence number.
        long maxSeq = 0;
        try {
            List<WalEntry> existing = readAll();
            for (WalEntry e : existing) {
                if (e.sequenceNumber() > maxSeq) maxSeq = e.sequenceNumber();
            }
        } catch (Exception ignored) {
            // If the WAL is empty or fully corrupt, start at 0.
        }
        this.nextSequence = new AtomicLong(maxSeq + 1);
    }

    /**
     * Appends a PUT entry to the WAL and fsyncs before returning.
     * Returns the sequence number assigned to this entry.
     */
    public synchronized long appendPut(String key, String value) throws IOException {
        long seq = nextSequence.getAndIncrement();
        WalEntry entry = WalEntry.put(seq, key, value);
        writeAndSync(entry);
        return seq;
    }

    /**
     * Appends a DELETE entry to the WAL and fsyncs before returning.
     */
    public synchronized long appendDelete(String key) throws IOException {
        long seq = nextSequence.getAndIncrement();
        WalEntry entry = WalEntry.delete(seq, key);
        writeAndSync(entry);
        return seq;
    }

    /**
     * Replays all valid entries in the WAL, calling consumer for each.
     * Stops silently at a corrupt or truncated entry (crash boundary).
     */
    public void replay(Consumer<WalEntry> consumer) throws IOException {
        List<WalEntry> entries = readAll();
        entries.forEach(consumer);
    }

    /**
     * Truncates the WAL file to zero bytes. Called after a MemTable is successfully
     * flushed to an SSTable — the WAL is no longer needed for those entries.
     */
    public synchronized void truncate() throws IOException {
        channel.truncate(0);
        channel.force(true);
    }

    public long currentSequence() {
        return nextSequence.get();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void writeAndSync(WalEntry entry) throws IOException {
        ByteBuffer buf = entry.encode();
        channel.position(channel.size()); // always append
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(false); // fsync data (not necessarily metadata)
    }

    private List<WalEntry> readAll() throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        long fileSize = channel.size();
        if (fileSize == 0) return entries;

        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(fileSize, Integer.MAX_VALUE));
        channel.position(0);
        while (buf.hasRemaining() && channel.position() < fileSize) {
            channel.read(buf);
        }
        buf.flip();

        while (buf.hasRemaining()) {
            int savedPos = buf.position();
            try {
                WalEntry entry = WalEntry.decode(buf);
                if (entry == null) break; // truncated tail
                entries.add(entry);
            } catch (WalCorruptionException e) {
                // Corrupt tail entry — stop replay here.
                buf.position(savedPos);
                break;
            }
        }
        return entries;
    }
}
