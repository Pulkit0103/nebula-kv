package com.nebulakv.cluster;

import com.nebulakv.cluster.GossipState.NodeEntry;
import com.nebulakv.core.NodeStatus;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gossip protocol engine — periodic membership state dissemination.
 *
 * Each gossip round:
 *   1. Increment this node's version counter.
 *   2. Update local GossipState with the new version.
 *   3. Select fanout peers at random from known live nodes.
 *   4. Push the local digest to each selected peer.
 *   5. Each peer merges the digest (higher version wins).
 *
 * In this portfolio implementation, "push" is simulated by direct method call
 * (no real RPC). A production implementation would serialize the digest and
 * send it over the wire protocol from Phase 3.
 *
 * Convergence: with fanout=3 and N nodes, full dissemination completes in
 * ceil(log_3(N)) rounds — typically 3-5 rounds for clusters up to 100 nodes.
 */
public final class GossipProtocol {

    static final long DEFAULT_INTERVAL_MS = 1_000;
    static final int  DEFAULT_FANOUT      = 3;

    private final String localNodeId;
    private final GossipState state;
    private final int fanout;
    private final long intervalMs;
    private final AtomicLong version = new AtomicLong(0);
    private final Random random = new Random();

    // Other nodes' gossip engines (stand-in for RPC in portfolio scope).
    private final List<GossipProtocol> peers = new ArrayList<>();

    private final ScheduledExecutorService scheduler;

    public GossipProtocol(String localNodeId, GossipState state) {
        this(localNodeId, state, DEFAULT_FANOUT, DEFAULT_INTERVAL_MS);
    }

    public GossipProtocol(String localNodeId, GossipState state, int fanout, long intervalMs) {
        this.localNodeId = localNodeId;
        this.state = state;
        this.fanout = fanout;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nebula-gossip-" + localNodeId);
            t.setDaemon(true);
            return t;
        });
        // Seed own entry at version 0.
        state.update(localNodeId, NodeStatus.ACTIVE, 0L);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::gossipRound,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
    }

    /** Registers another node's gossip engine for direct-call simulation. */
    public void addPeer(GossipProtocol peer) {
        peers.add(peer);
    }

    /** Returns the local gossip state (for inspection in tests). */
    public GossipState localState() {
        return state;
    }

    public String nodeId() {
        return localNodeId;
    }

    /**
     * Receives an incoming gossip digest from another node and merges it.
     */
    public void receive(Map<String, NodeEntry> incoming) {
        state.merge(incoming);
    }

    // -------------------------------------------------------------------------
    // Gossip round
    // -------------------------------------------------------------------------

    void gossipRound() {
        // Increment version, update own entry.
        long v = version.incrementAndGet();
        state.update(localNodeId, NodeStatus.ACTIVE, v);

        if (peers.isEmpty()) return;

        // Select up to fanout random peers.
        List<GossipProtocol> shuffled = new ArrayList<>(peers);
        Collections.shuffle(shuffled, random);
        int count = Math.min(fanout, shuffled.size());

        Map<String, NodeEntry> digest = state.digest();
        for (int i = 0; i < count; i++) {
            shuffled.get(i).receive(digest);
        }
    }
}
