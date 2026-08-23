package com.nebulakv.raft;

import java.util.List;

/**
 * Sent by the LEADER to replicate log entries and as a heartbeat (entries empty).
 *
 * @param term         leader's current term
 * @param leaderId     node ID of the leader
 * @param prevLogIndex index of the log entry immediately preceding the new ones
 * @param prevLogTerm  term of prevLogIndex entry
 * @param entries      entries to append (empty for heartbeat)
 * @param leaderCommit leader's commit index
 */
public record AppendEntriesRequest(
        long term,
        String leaderId,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit) {}
