package com.nebulakv.cluster;

import com.nebulakv.cluster.GossipState.NodeEntry;
import com.nebulakv.core.NodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Gossip — membership state dissemination")
class GossipTest {

    @Test
    @DisplayName("GossipState merge: higher version replaces lower")
    void mergeHigherVersionWins() {
        GossipState state = new GossipState();
        state.update("nodeA", NodeStatus.ACTIVE, 1L);

        int updated = state.merge(Map.of(
                "nodeA", new NodeEntry("nodeA", NodeStatus.SUSPECT, 5L)
        ));

        assertEquals(1, updated);
        assertEquals(NodeStatus.SUSPECT, state.entryFor("nodeA").status());
        assertEquals(5L, state.entryFor("nodeA").version());
    }

    @Test
    @DisplayName("GossipState merge: lower version is ignored")
    void mergeLowerVersionIgnored() {
        GossipState state = new GossipState();
        state.update("nodeA", NodeStatus.ACTIVE, 10L);

        int updated = state.merge(Map.of(
                "nodeA", new NodeEntry("nodeA", NodeStatus.SUSPECT, 3L)
        ));

        assertEquals(0, updated);
        assertEquals(NodeStatus.ACTIVE, state.entryFor("nodeA").status());
    }

    @Test
    @DisplayName("GossipState merge: unknown node is added")
    void mergeAddsUnknownNode() {
        GossipState state = new GossipState();

        state.merge(Map.of(
                "nodeB", new NodeEntry("nodeB", NodeStatus.ACTIVE, 2L)
        ));

        assertNotNull(state.entryFor("nodeB"));
        assertEquals(NodeStatus.ACTIVE, state.entryFor("nodeB").status());
    }

    @Test
    @DisplayName("gossipRound propagates local state to peers")
    void gossipRoundPropagates() {
        GossipState stateA = new GossipState();
        GossipState stateB = new GossipState();

        GossipProtocol nodeA = new GossipProtocol("nodeA", stateA, 1, 100);
        GossipProtocol nodeB = new GossipProtocol("nodeB", stateB, 1, 100);

        nodeA.addPeer(nodeB);
        nodeB.addPeer(nodeA);

        // Manually trigger one round from nodeA.
        nodeA.gossipRound();

        // nodeB should now know about nodeA.
        assertNotNull(stateB.entryFor("nodeA"),
                "nodeB must learn about nodeA after one gossip round");
    }

    @Test
    @DisplayName("gossip converges across three nodes in two rounds")
    void gossipConvergesThreeNodes() {
        GossipState stateA = new GossipState();
        GossipState stateB = new GossipState();
        GossipState stateC = new GossipState();

        GossipProtocol nodeA = new GossipProtocol("nodeA", stateA, 2, 100);
        GossipProtocol nodeB = new GossipProtocol("nodeB", stateB, 2, 100);
        GossipProtocol nodeC = new GossipProtocol("nodeC", stateC, 2, 100);

        // Full mesh
        nodeA.addPeer(nodeB); nodeA.addPeer(nodeC);
        nodeB.addPeer(nodeA); nodeB.addPeer(nodeC);
        nodeC.addPeer(nodeA); nodeC.addPeer(nodeB);

        // Round 1: each node gossips to both peers
        nodeA.gossipRound();
        nodeB.gossipRound();
        nodeC.gossipRound();

        // After one full round all nodes should know all others.
        assertEquals(3, stateA.size(), "nodeA should know 3 nodes after round");
        assertEquals(3, stateB.size(), "nodeB should know 3 nodes after round");
        assertEquals(3, stateC.size(), "nodeC should know 3 nodes after round");
    }

    @Test
    @DisplayName("gossipRound increments local version each call")
    void versionIncrements() {
        GossipState state = new GossipState();
        GossipProtocol node = new GossipProtocol("nodeA", state, 0, 100);

        node.gossipRound();
        node.gossipRound();
        node.gossipRound();

        long v = state.entryFor("nodeA").version();
        assertEquals(3L, v, "Version should be 3 after three rounds");
    }
}
