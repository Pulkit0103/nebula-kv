package com.nebulakv.raft;

/**
 * One entry in the Raft log.
 *
 * Raft log indexes are 1-based. Index 0 is a sentinel (term=0, NoOp) used
 * to simplify boundary conditions — callers never need to special-case "empty log".
 */
public record LogEntry(long index, long term, RaftCommand command) {

    /** Sentinel entry at index 0. Every log starts with this. */
    public static final LogEntry SENTINEL = new LogEntry(0, 0, new RaftCommand.NoOp());

    public LogEntry {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        if (term  < 0) throw new IllegalArgumentException("term must be >= 0");
    }
}
