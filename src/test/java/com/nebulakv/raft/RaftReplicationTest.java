package com.nebulakv.raft;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Raft log replication (Phase 43).
 *
 * Tests verify AppendEntries consistency checks, commit-index advancement,
 * and the follower catch-up mechanism.
 */
@DisplayName("Raft — log replication")
class RaftReplicationTest {

    private InProcessRaftTransport transport;
    private RaftNode n1, n2, n3;

    @BeforeEach
    void setup() {
        transport = new InProcessRaftTransport();
        n1 = new RaftNode("n1", List.of("n2", "n3"), transport);
        n2 = new RaftNode("n2", List.of("n1", "n3"), transport);
        n3 = new RaftNode("n3", List.of("n1", "n2"), transport);
        transport.register(n1); transport.register(n2); transport.register(n3);
    }

    @AfterEach
    void tearDown() { n1.shutdown(); n2.shutdown(); n3.shutdown(); }

    /** Waits up to 2s for one leader. */
    private RaftNode waitForLeader() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode n : List.of(n1, n2, n3)) {
                if (n.isLeader()) return n;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // AppendEntries consistency check tests (unit — no election needed)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AppendEntries: rejects stale leader term")
    void rejectStaleTerm() {
        // Bump n1 to term 5
        n1.handleRequestVote(new RequestVoteRequest(5, "n2", 0, 0));
        AppendEntriesResponse resp = n1.handleAppendEntries(
                new AppendEntriesRequest(3, "n2", 0, 0, List.of(), 0));
        assertFalse(resp.success());
        assertEquals(5, resp.term());
    }

    @Test
    @DisplayName("AppendEntries: rejects when prevLogIndex/term don't match")
    void rejectPrevLogMismatch() {
        // n1 has only the sentinel; a request claiming prevLogIndex=5 must fail
        AppendEntriesResponse resp = n1.handleAppendEntries(
                new AppendEntriesRequest(1, "n2", 5, 1, List.of(), 0));
        assertFalse(resp.success());
    }

    @Test
    @DisplayName("AppendEntries: heartbeat (empty entries) succeeds and resets timer")
    void heartbeatSucceeds() {
        AppendEntriesResponse resp = n1.handleAppendEntries(
                new AppendEntriesRequest(1, "leader", 0, 0, List.of(), 0));
        assertTrue(resp.success());
        assertEquals(RaftRole.FOLLOWER, n1.role());
    }

    @Test
    @DisplayName("AppendEntries: appends new entries correctly")
    void appendNewEntries() {
        LogEntry e1 = new LogEntry(1, 1, new RaftCommand.Put("k", "v"));
        AppendEntriesResponse resp = n1.handleAppendEntries(
                new AppendEntriesRequest(1, "leader", 0, 0, List.of(e1), 0));
        assertTrue(resp.success());
        assertEquals(1, resp.matchIndex());
    }

    @Test
    @DisplayName("AppendEntries: truncates conflicting suffix before appending")
    void truncateConflict() {
        // Append a term-1 entry at index 1
        n1.handleAppendEntries(new AppendEntriesRequest(1, "old-leader", 0, 0,
                List.of(new LogEntry(1, 1, new RaftCommand.Put("x", "bad"))), 0));

        // New leader (term 2) sends a different entry for index 1
        AppendEntriesResponse resp = n1.handleAppendEntries(
                new AppendEntriesRequest(2, "new-leader", 0, 0,
                        List.of(new LogEntry(1, 2, new RaftCommand.Put("x", "good"))), 0));
        assertTrue(resp.success());
        assertEquals(1, resp.matchIndex());
    }

    @Test
    @DisplayName("AppendEntries: advances commitIndex when leaderCommit is higher")
    void advancesCommitIndex() {
        LogEntry e1 = new LogEntry(1, 1, new RaftCommand.Put("a", "1"));
        // First call: append, no commit yet
        n1.handleAppendEntries(new AppendEntriesRequest(1, "leader", 0, 0, List.of(e1), 0));
        assertEquals(0, n1.commitIndex());

        // Second call (heartbeat): leader commits entry 1
        n1.handleAppendEntries(new AppendEntriesRequest(1, "leader", 1, 1, List.of(), 1));
        assertEquals(1, n1.commitIndex());
    }

    // -------------------------------------------------------------------------
    // End-to-end replication through propose()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("propose on leader replicates to all followers")
    void proposeReplicatesToFollowers() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader, "No leader elected");

        leader.propose(new RaftCommand.Put("city", "Tokyo"));

        // All nodes should have commitIndex >= 1 (the no-op + the put)
        for (RaftNode n : List.of(n1, n2, n3)) {
            assertTrue(n.commitIndex() >= 1,
                    n.nodeId() + " commitIndex=" + n.commitIndex());
        }
    }

    @Test
    @DisplayName("propose on non-leader throws IllegalStateException")
    void proposeOnNonLeaderThrows() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);
        RaftNode follower = List.of(n1, n2, n3).stream()
                .filter(n -> !n.isLeader()).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> follower.propose(new RaftCommand.Put("k", "v")));
    }

    @Test
    @DisplayName("multiple proposals all commit on all nodes")
    void multipleProposals() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);

        for (int i = 0; i < 5; i++) {
            leader.propose(new RaftCommand.Put("key" + i, "val" + i));
        }

        long leaderCommit = leader.commitIndex();
        for (RaftNode n : List.of(n1, n2, n3)) {
            assertEquals(leaderCommit, n.commitIndex(),
                    n.nodeId() + " did not reach commit " + leaderCommit);
        }
    }

    @Test
    @DisplayName("follower that was partitioned catches up after reconnect")
    void followerCatchesUpAfterReconnect() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);

        // Partition one follower
        RaftNode laggard = List.of(n1, n2, n3).stream()
                .filter(n -> !n.isLeader()).findFirst().orElseThrow();
        transport.partition(laggard.nodeId());

        // Write some entries while the follower is partitioned
        leader.propose(new RaftCommand.Put("a", "1"));
        leader.propose(new RaftCommand.Put("b", "2"));

        // Reconnect the follower
        transport.heal(laggard);

        // Next proposal will trigger replication that catches the laggard up
        leader.propose(new RaftCommand.Put("c", "3"));

        assertEquals(leader.commitIndex(), laggard.commitIndex());
    }
}
