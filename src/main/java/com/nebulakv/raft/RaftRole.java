package com.nebulakv.raft;

/** A Raft node's current role in the cluster. */
public enum RaftRole {
    /** Passive — accepts log entries from the leader and votes in elections. */
    FOLLOWER,
    /** Actively seeking votes to become the new leader. */
    CANDIDATE,
    /** Drives all writes; sends heartbeats to suppress new elections. */
    LEADER
}
