package com.nebulakv.cluster;

import com.nebulakv.core.NodeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FailureDetector — heartbeat and failure transition tests")
class FailureDetectorTest {

    private MembershipManager membership;
    private FailureDetector detector;

    @BeforeEach
    void setUp() {
        membership = new MembershipManager();
        // Use very short thresholds so tests don't need to wait.
        // Short thresholds + 50ms check interval so tests don't need to wait long.
        detector = new FailureDetector(membership, 100, 300, 50);
    }

    @AfterEach
    void tearDown() {
        detector.stop();
    }

    @Test
    @DisplayName("registerNode tracks the node")
    void registerNodeTracks() {
        detector.registerNode("node1");
        assertTrue(detector.trackedNodes().contains("node1"));
    }

    @Test
    @DisplayName("unregisterNode stops tracking")
    void unregisterNodeStopsTracking() {
        detector.registerNode("node1");
        detector.unregisterNode("node1");
        assertFalse(detector.trackedNodes().contains("node1"));
    }

    @Test
    @DisplayName("heartbeat resets the timer")
    void heartbeatResetsTimer() throws InterruptedException {
        detector.registerNode("node1");
        Thread.sleep(50);
        detector.heartbeat("node1");
        long elapsed = detector.msSinceLastHeartbeat("node1");
        assertTrue(elapsed < 100, "Elapsed must be near zero after heartbeat, got " + elapsed);
    }

    @Test
    @DisplayName("node with no heartbeat is marked SUSPECT after suspect threshold")
    void nodeMarkedSuspectAfterThreshold() throws InterruptedException {
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        detector.registerNode("node1");
        detector.start();

        // Wait longer than suspect threshold (100ms) but less than down threshold (300ms).
        Thread.sleep(250);

        Optional<NodeStatus> status = membership.statusOf("node1");
        // Should be SUSPECT or DOWN by now.
        assertTrue(
            status.map(s -> s == NodeStatus.SUSPECT || s == NodeStatus.DOWN).orElse(false),
            "Node should be SUSPECT or DOWN after missed heartbeats, status=" + status
        );
    }

    @Test
    @DisplayName("msSinceLastHeartbeat returns -1 for unknown node")
    void unknownNodeReturnsMinusOne() {
        assertEquals(-1, detector.msSinceLastHeartbeat("phantom"));
    }
}
