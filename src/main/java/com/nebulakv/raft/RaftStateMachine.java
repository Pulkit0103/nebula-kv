package com.nebulakv.raft;

/**
 * Callback interface for the Raft state machine.
 *
 * Once an entry is committed by a majority, RaftNode calls apply() exactly once,
 * in log order, with no gaps. Implementations update durable state (e.g. a KV store).
 */
public interface RaftStateMachine {
    void apply(LogEntry entry);
}
