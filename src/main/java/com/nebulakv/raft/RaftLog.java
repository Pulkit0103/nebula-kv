package com.nebulakv.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory Raft log.
 *
 * Invariants:
 *   - Index 0 is always the sentinel entry (term=0). Real entries start at index 1.
 *   - Entries are stored at entries.get((int) index) — the list is the index.
 *   - The log is append-only except for truncateSuffix, which removes conflicting
 *     entries when a follower receives AppendEntries with a lower prevLogIndex.
 *
 * Thread safety: NOT thread-safe. RaftNode synchronises access externally.
 */
public final class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();

    public RaftLog() {
        entries.add(LogEntry.SENTINEL); // slot 0
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Appends one entry, assigning it the next index automatically.
     *
     * @return the index of the appended entry
     */
    public long append(long term, RaftCommand command) {
        long index = entries.size(); // next slot = current size
        entries.add(new LogEntry(index, term, command));
        return index;
    }

    /**
     * Appends a pre-built entry (used during AppendEntries replication).
     * The entry's index must equal the current last index + 1.
     */
    public void appendEntry(LogEntry entry) {
        if (entry.index() != entries.size()) {
            throw new IllegalArgumentException(
                "Expected index " + entries.size() + ", got " + entry.index());
        }
        entries.add(entry);
    }

    /**
     * Removes all entries with index >= {@code fromIndex}.
     * Used by followers to resolve log conflicts.
     */
    public void truncateSuffix(long fromIndex) {
        if (fromIndex <= 0) throw new IllegalArgumentException("fromIndex must be >= 1");
        int from = (int) fromIndex;
        if (from < entries.size()) {
            entries.subList(from, entries.size()).clear();
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /** Returns the entry at {@code index}, or the sentinel if index == 0. */
    public LogEntry getEntry(long index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("No entry at index " + index);
        }
        return entries.get((int) index);
    }

    /** Index of the last entry (0 if only the sentinel exists). */
    public long lastIndex() {
        return entries.size() - 1L;
    }

    /** Term of the last entry (0 if only the sentinel exists). */
    public long lastTerm() {
        return entries.get(entries.size() - 1).term();
    }

    /**
     * Returns true if the log contains an entry at {@code index} with matching {@code term}.
     * Index 0 always matches (sentinel).
     */
    public boolean containsMatchingEntry(long index, long term) {
        if (index == 0) return true; // sentinel always matches
        if (index >= entries.size()) return false;
        return entries.get((int) index).term() == term;
    }

    /**
     * Returns entries in the half-open range [fromIndex, toIndex).
     * Safe to call with toIndex > lastIndex — clamps automatically.
     */
    public List<LogEntry> slice(long fromIndex, long toIndex) {
        int from = (int) Math.max(1, fromIndex);
        int to   = (int) Math.min(entries.size(), toIndex);
        if (from >= to) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(from, to)));
    }

    /** All entries after index {@code afterIndex} (i.e. from afterIndex+1 to end). */
    public List<LogEntry> entriesAfter(long afterIndex) {
        return slice(afterIndex + 1, entries.size());
    }

    /** Total number of entries including the sentinel. */
    public int size() {
        return entries.size();
    }
}
