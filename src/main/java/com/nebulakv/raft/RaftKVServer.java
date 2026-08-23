package com.nebulakv.raft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade that ties together a Raft cluster into a coherent key-value service.
 *
 * Each node in the cluster owns one RaftKVServer. The server holds:
 *   - a RaftNode (consensus layer)
 *   - a RaftKVStore (state machine + write path)
 *
 * Write operations (put, delete) are transparently forwarded to the current
 * leader via the in-process transport. If this node IS the leader the call is
 * executed directly; if it is a follower a NotLeaderException is thrown so
 * the caller can retry against the node identified by leaderId().
 *
 * Read operations are served from the local state machine (eventual
 * consistency). For strict linearisable reads, callers should only read
 * from the leader — that enforcement is left to the upper layer.
 */
public final class RaftKVServer {

    /** Thrown when a write is attempted on a non-leader node. */
    public static final class NotLeaderException extends RuntimeException {
        private final String leaderId;

        public NotLeaderException(String leaderId) {
            super("Not the leader. Current leader: " + leaderId);
            this.leaderId = leaderId;
        }

        /** The nodeId of the current leader, or null if unknown. */
        public String leaderId() { return leaderId; }
    }

    // -------------------------------------------------------------------------

    private final RaftKVStore store;

    public RaftKVServer(RaftNode node) {
        this.store = new RaftKVStore(node);
    }

    // -------------------------------------------------------------------------
    // Write path (leader only)
    // -------------------------------------------------------------------------

    /**
     * Put a key. Redirects (throws NotLeaderException) if not the leader.
     */
    public void put(String key, String value) throws InterruptedException {
        ensureLeader();
        store.put(key, value);
    }

    /**
     * Delete a key. Redirects (throws NotLeaderException) if not the leader.
     */
    public void delete(String key) throws InterruptedException {
        ensureLeader();
        store.delete(key);
    }

    // -------------------------------------------------------------------------
    // Read path (local state machine)
    // -------------------------------------------------------------------------

    public String get(String key) {
        return store.get(key);
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    public boolean isLeader()       { return store.node().isLeader(); }
    public String  nodeId()         { return store.node().nodeId(); }
    public String  leaderId()       { return store.node().leaderId(); }
    public long    commitIndex()    { return store.node().commitIndex(); }
    public RaftNode node()          { return store.node(); }

    // -------------------------------------------------------------------------

    private void ensureLeader() {
        if (!store.node().isLeader()) {
            throw new NotLeaderException(store.node().leaderId());
        }
    }
}
