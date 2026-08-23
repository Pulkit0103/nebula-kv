package com.nebulakv.raft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process RaftTransport for tests.
 *
 * All registered nodes share one instance. RPC calls are direct method
 * invocations — no serialization, no network. Nodes can be removed to
 * simulate partitions or crashes.
 */
public final class InProcessRaftTransport implements RaftTransport {

    private final Map<String, RaftNode> nodes = new ConcurrentHashMap<>();

    /** Register a node so it can receive RPCs. */
    public void register(RaftNode node) {
        nodes.put(node.nodeId(), node);
    }

    /** Remove a node to simulate a partition or crash. */
    public void partition(String nodeId) {
        nodes.remove(nodeId);
    }

    /** Re-add a previously partitioned node. */
    public void heal(RaftNode node) {
        nodes.put(node.nodeId(), node);
    }

    @Override
    public RequestVoteResponse requestVote(String peerId, RequestVoteRequest request) {
        RaftNode peer = nodes.get(peerId);
        if (peer == null) throw new RaftTransportException("Peer unreachable: " + peerId);
        return peer.handleRequestVote(request);
    }

    @Override
    public AppendEntriesResponse appendEntries(String peerId, AppendEntriesRequest request) {
        RaftNode peer = nodes.get(peerId);
        if (peer == null) throw new RaftTransportException("Peer unreachable: " + peerId);
        return peer.handleAppendEntries(request);
    }
}
