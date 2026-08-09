package com.nebulakv.cluster;

import com.nebulakv.core.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MembershipManager — node lifecycle transitions")
class MembershipManagerTest {

    private MembershipManager manager;

    @BeforeEach
    void setUp() {
        manager = new MembershipManager();
    }

    @Test
    @DisplayName("join adds node as ACTIVE")
    void joinAddsNodeAsActive() {
        manager.join(ClusterNode.active("node1", "localhost", 7001));
        assertEquals(Optional.of(NodeStatus.ACTIVE), manager.statusOf("node1"));
    }

    @Test
    @DisplayName("leave removes the node")
    void leaveRemovesNode() {
        manager.join(ClusterNode.active("node1", "localhost", 7001));
        manager.leave("node1");
        assertFalse(manager.contains("node1"));
    }

    @Test
    @DisplayName("suspect transitions to SUSPECT")
    void suspectTransition() {
        manager.join(ClusterNode.active("node1", "localhost", 7001));
        manager.suspect("node1");
        assertEquals(Optional.of(NodeStatus.SUSPECT), manager.statusOf("node1"));
    }

    @Test
    @DisplayName("markDown transitions to DOWN")
    void markDownTransition() {
        manager.join(ClusterNode.active("node1", "localhost", 7001));
        manager.markDown("node1");
        assertEquals(Optional.of(NodeStatus.DOWN), manager.statusOf("node1"));
    }

    @Test
    @DisplayName("activeMembers returns only ACTIVE nodes")
    void activeMembersFiltersCorrectly() {
        manager.join(ClusterNode.active("node1", "localhost", 7001));
        manager.join(ClusterNode.active("node2", "localhost", 7002));
        manager.join(ClusterNode.active("node3", "localhost", 7003));
        manager.suspect("node2");
        manager.markDown("node3");

        List<ClusterNode> active = manager.activeMembers();
        assertEquals(1, active.size());
        assertEquals("node1", active.get(0).nodeId());
    }

    @Test
    @DisplayName("statusOf returns empty for unknown node")
    void statusOfUnknownNodeReturnsEmpty() {
        assertEquals(Optional.empty(), manager.statusOf("phantom"));
    }
}
