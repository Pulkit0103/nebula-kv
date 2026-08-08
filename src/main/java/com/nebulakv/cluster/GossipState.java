package com.nebulakv.cluster;

import com.nebulakv.core.NodeStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-local view of cluster membership, disseminated via gossip.
 *
 * Each node maintains a version counter per peer. On each gossip round,
 * a node selects a random peer and exchanges its full state digest.
 * The receiver merges incoming entries: if the incoming version is higher
 * than the local version for that node, the local entry is updated.
 *
 * This is a simplified push-pull gossip model:
 *   - Push: sender transmits its full state digest.
 *   - Pull: receiver applies any updates where incoming version > local version.
 *
 * Why gossip over a central coordinator?
 *   Gossip disseminates information in O(log N) rounds with no single point of failure.
 *   Each node only needs to know a small random subset of its peers per round.
 *   Convergence time is proportional to log(N), independent of cluster size.
 */
public final class GossipState {

    public record NodeEntry(String nodeId, NodeStatus status, long version) {}

    // nodeId → latest known entry
    private final ConcurrentHashMap<String, NodeEntry> entries = new ConcurrentHashMap<>();

    /** Adds or updates the local node's own entry. */
    public void update(String nodeId, NodeStatus status, long version) {
        entries.merge(nodeId, new NodeEntry(nodeId, status, version),
                (existing, incoming) -> incoming.version() > existing.version() ? incoming : existing);
    }

    /**
     * Merges a remote gossip digest into local state.
     * For each entry in the incoming digest, if its version is higher than
     * the local version, the local entry is replaced.
     *
     * @return number of entries that were updated
     */
    public int merge(Map<String, NodeEntry> incoming) {
        int updated = 0;
        for (Map.Entry<String, NodeEntry> e : incoming.entrySet()) {
            NodeEntry remote = e.getValue();
            NodeEntry local = entries.get(e.getKey());
            if (local == null || remote.version() > local.version()) {
                entries.put(e.getKey(), remote);
                updated++;
            }
        }
        return updated;
    }

    /** Returns the current local digest (snapshot of all known entries). */
    public Map<String, NodeEntry> digest() {
        return Map.copyOf(entries);
    }

    /** Returns the current entry for a node, or null if unknown. */
    public NodeEntry entryFor(String nodeId) {
        return entries.get(nodeId);
    }

    public int size() {
        return entries.size();
    }
}
