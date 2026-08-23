package com.nebulakv.raft;

/**
 * Abstraction over the network layer for Raft RPCs.
 *
 * Implementations:
 *   InProcessRaftTransport — direct method calls, used in tests
 *   (future) TcpRaftTransport — binary-framed TCP calls for real clusters
 */
public interface RaftTransport {

    /**
     * Sends a RequestVote RPC to the peer identified by {@code peerId}.
     * Blocks until a response arrives or the call fails.
     *
     * @throws RaftTransportException if the peer is unreachable
     */
    RequestVoteResponse requestVote(String peerId, RequestVoteRequest request);

    /**
     * Sends an AppendEntries RPC (heartbeat or replication) to {@code peerId}.
     * Blocks until a response arrives or the call fails.
     *
     * @throws RaftTransportException if the peer is unreachable
     */
    AppendEntriesResponse appendEntries(String peerId, AppendEntriesRequest request);
}
