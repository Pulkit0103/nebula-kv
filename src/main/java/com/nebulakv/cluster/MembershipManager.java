package com.nebulakv.cluster;

import com.nebulakv.core.NodeStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks cluster membership and node lifecycle transitions.
 *
 * Node states:
 *   STANDALONE → JOINING → ACTIVE → SUSPECT → DOWN → LEAVING
 *
 * This implementation is single-node membership tracking (no gossip yet).
 * Phase 22 will replace this with gossip-based dissemination.
 *
 * Thread-safe via ConcurrentHashMap.
 */
public final class MembershipManager {

    private final ConcurrentHashMap<String, ClusterNode> members = new ConcurrentHashMap<>();

    /**
     * Registers a node as JOINING, then immediately marks it ACTIVE.
     * Real implementation would wait for data transfer before ACTIVE.
     */
    public void join(ClusterNode node) {
        ClusterNode joining = new ClusterNode(node.nodeId(), node.host(), node.port(), NodeStatus.JOINING);
        members.put(node.nodeId(), joining);
        // Immediately transition to ACTIVE (Phase 21 will gate this on data transfer).
        ClusterNode active = new ClusterNode(node.nodeId(), node.host(), node.port(), NodeStatus.ACTIVE);
        members.put(node.nodeId(), active);
    }

    /**
     * Marks a node as LEAVING and then removes it.
     */
    public void leave(String nodeId) {
        members.computeIfPresent(nodeId, (id, node) ->
                new ClusterNode(node.nodeId(), node.host(), node.port(), NodeStatus.LEAVING));
        members.remove(nodeId);
    }

    /**
     * Marks a node SUSPECT (missed heartbeats threshold reached).
     */
    public void suspect(String nodeId) {
        members.computeIfPresent(nodeId, (id, node) ->
                new ClusterNode(node.nodeId(), node.host(), node.port(), NodeStatus.SUSPECT));
    }

    /**
     * Marks a node DOWN (confirmed failure).
     */
    public void markDown(String nodeId) {
        members.computeIfPresent(nodeId, (id, node) ->
                new ClusterNode(node.nodeId(), node.host(), node.port(), NodeStatus.DOWN));
    }

    /**
     * Returns the current status of a node, or empty if not known.
     */
    public Optional<NodeStatus> statusOf(String nodeId) {
        ClusterNode node = members.get(nodeId);
        return node == null ? Optional.empty() : Optional.of(node.status());
    }

    /**
     * Returns all ACTIVE members.
     */
    public List<ClusterNode> activeMembers() {
        return members.values().stream()
                .filter(n -> n.status() == NodeStatus.ACTIVE)
                .toList();
    }

    public int size() {
        return members.size();
    }

    public boolean contains(String nodeId) {
        return members.containsKey(nodeId);
    }
}
