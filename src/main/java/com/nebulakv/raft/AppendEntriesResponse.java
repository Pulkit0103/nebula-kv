package com.nebulakv.raft;

/**
 * Response to an AppendEntries RPC.
 *
 * @param term       follower's current term (leader steps down if stale)
 * @param success    true if the follower accepted the entries
 * @param matchIndex the follower's log index after a successful append (for leader bookkeeping)
 */
public record AppendEntriesResponse(long term, boolean success, long matchIndex) {}
