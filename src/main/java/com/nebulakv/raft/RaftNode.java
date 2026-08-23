package com.nebulakv.raft;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core Raft node implementing leader election and log replication.
 *
 * Implements the Raft consensus algorithm as described in:
 *   "In Search of an Understandable Consensus Algorithm" — Ongaro & Ousterhout, 2014
 *
 * Threading model:
 *   - All state mutations are guarded by the intrinsic lock (synchronized methods).
 *   - The election timer runs on a dedicated daemon thread.
 *   - RPC handlers (handleRequestVote, handleAppendEntries) are called by the
 *     transport layer and synchronise on entry.
 *   - The apply loop runs on a dedicated daemon thread (Phase 44).
 *
 * Raft persistent state (currentTerm, votedFor, log) is in-memory here.
 * Persistence to disk is left for a future phase.
 */
public final class RaftNode {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    static final int ELECTION_TIMEOUT_MIN_MS = 150;
    static final int ELECTION_TIMEOUT_MAX_MS = 300;
    static final int HEARTBEAT_INTERVAL_MS   = 50;

    private final String nodeId;
    private final List<String> peerIds;       // all other nodes in the cluster
    private final RaftTransport transport;
    private final RaftLog log;

    // -------------------------------------------------------------------------
    // Persistent state (would be written to disk in production)
    // -------------------------------------------------------------------------

    private long currentTerm = 0;
    private String votedFor  = null;   // null = not voted this term

    // -------------------------------------------------------------------------
    // Volatile state (all nodes)
    // -------------------------------------------------------------------------

    private long commitIndex = 0;
    private long lastApplied = 0;
    private RaftRole role = RaftRole.FOLLOWER;
    private String leaderId = null;

    // -------------------------------------------------------------------------
    // Volatile state (leader only) — resets on each new election
    // -------------------------------------------------------------------------

    private final Map<String, Long> nextIndex  = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();

    // -------------------------------------------------------------------------
    // Election timer
    // -------------------------------------------------------------------------

    private final Random rng = new Random();
    private volatile long electionDeadlineMs = nextDeadline();
    private final ScheduledExecutorService scheduler;

    // -------------------------------------------------------------------------
    // Apply callback (Phase 44)
    // -------------------------------------------------------------------------

    private volatile RaftStateMachine stateMachine = null;
    private final AtomicLong appliedCount = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public RaftNode(String nodeId, List<String> peerIds, RaftTransport transport) {
        this.nodeId    = nodeId;
        this.peerIds   = List.copyOf(peerIds);
        this.transport = transport;
        this.log       = new RaftLog();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-timer-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        // Tick every 10ms — checks whether election timeout has fired
        scheduler.scheduleAtFixedRate(this::tick, 10, 10, TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String nodeId()    { return nodeId; }
    public synchronized RaftRole role() { return role; }
    public synchronized long currentTerm() { return currentTerm; }
    public synchronized String leaderId()  { return leaderId; }
    public synchronized long commitIndex() { return commitIndex; }
    public synchronized long lastApplied() { return lastApplied; }

    /** Attach a state machine that receives committed commands (Phase 44). */
    public void setStateMachine(RaftStateMachine sm) { this.stateMachine = sm; }

    /** Returns true if this node believes it is the current leader. */
    public synchronized boolean isLeader() { return role == RaftRole.LEADER; }

    /**
     * Appends a command to the log and begins replication.
     * Only valid on the LEADER. Throws IllegalStateException if called on a non-leader.
     *
     * @return the log index the command was assigned
     */
    public synchronized long propose(RaftCommand command) {
        if (role != RaftRole.LEADER) {
            throw new IllegalStateException("Not the leader. Current leader: " + leaderId);
        }
        long index = log.append(currentTerm, command);
        // Immediately replicate to all followers (blocking in this simple model)
        replicateToAll();
        return index;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // RPC handlers (called by RaftTransport)
    // -------------------------------------------------------------------------

    /**
     * Handles an incoming RequestVote RPC. Raft §5.2 and §5.4.
     */
    public synchronized RequestVoteResponse handleRequestVote(RequestVoteRequest req) {
        // If we see a higher term, convert to follower immediately.
        if (req.term() > currentTerm) {
            stepDown(req.term());
        }

        boolean grant = false;
        if (req.term() >= currentTerm) {
            boolean notVotedYet = votedFor == null || votedFor.equals(req.candidateId());
            boolean candidateLogUpToDate = isCandidateLogUpToDate(req.lastLogIndex(), req.lastLogTerm());
            if (notVotedYet && candidateLogUpToDate) {
                votedFor = req.candidateId();
                grant    = true;
                resetElectionTimer(); // we voted; reset our own timer
            }
        }
        return new RequestVoteResponse(currentTerm, grant);
    }

    /**
     * Handles an incoming AppendEntries RPC (heartbeat or replication). Raft §5.3.
     */
    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest req) {
        if (req.term() < currentTerm) {
            return new AppendEntriesResponse(currentTerm, false, log.lastIndex());
        }

        // Valid leader — reset our election timer and record who the leader is.
        if (req.term() > currentTerm) {
            stepDown(req.term());
        } else if (role == RaftRole.CANDIDATE) {
            // A peer won the election while we were campaigning.
            role = RaftRole.FOLLOWER;
        }
        leaderId = req.leaderId();
        resetElectionTimer();

        // Consistency check: do we have prevLogIndex at prevLogTerm?
        if (!log.containsMatchingEntry(req.prevLogIndex(), req.prevLogTerm())) {
            return new AppendEntriesResponse(currentTerm, false, log.lastIndex());
        }

        // Append new entries (truncate any conflicting suffix first).
        long insertPos = req.prevLogIndex() + 1;
        for (LogEntry entry : req.entries()) {
            if (entry.index() < log.size() && log.getEntry(entry.index()).term() != entry.term()) {
                log.truncateSuffix(entry.index());
            }
            if (entry.index() >= log.size()) {
                log.appendEntry(entry);
            }
        }

        // Advance commitIndex.
        if (req.leaderCommit() > commitIndex) {
            commitIndex = Math.min(req.leaderCommit(), log.lastIndex());
            triggerApply();
        }

        return new AppendEntriesResponse(currentTerm, true, log.lastIndex());
    }

    // -------------------------------------------------------------------------
    // Election
    // -------------------------------------------------------------------------

    private void tick() {
        boolean shouldStartElection;
        synchronized (this) {
            shouldStartElection = role != RaftRole.LEADER
                    && System.currentTimeMillis() >= electionDeadlineMs;
        }
        if (shouldStartElection) startElection();
    }

    private synchronized void startElection() {
        role = RaftRole.CANDIDATE;
        currentTerm++;
        votedFor = nodeId; // vote for self
        leaderId = null;
        resetElectionTimer();

        long term         = currentTerm;
        long lastLogIndex = log.lastIndex();
        long lastLogTerm  = log.lastTerm();
        List<String> peers = new ArrayList<>(peerIds);

        // Release lock while making RPCs
        int votes = 1; // self-vote
        for (String peer : peers) {
            RequestVoteResponse resp;
            try {
                resp = transport.requestVote(peer,
                        new RequestVoteRequest(term, nodeId, lastLogIndex, lastLogTerm));
            } catch (RaftTransportException e) {
                continue; // peer unreachable — treat as denial
            }
            synchronized (this) {
                if (resp.term() > currentTerm) {
                    stepDown(resp.term());
                    return;
                }
            }
            if (resp.voteGranted()) votes++;
        }

        synchronized (this) {
            int quorum = (peerIds.size() + 1) / 2 + 1;
            if (role == RaftRole.CANDIDATE && currentTerm == term && votes >= quorum) {
                becomeLeader();
            }
        }
    }

    private void becomeLeader() {
        role     = RaftRole.LEADER;
        leaderId = nodeId;
        // Initialise leader bookkeeping
        for (String peer : peerIds) {
            nextIndex.put(peer,  log.lastIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        // Append a no-op to commit prior-term entries (Raft §8)
        log.append(currentTerm, new RaftCommand.NoOp());
        // Send immediate heartbeats
        replicateToAll();
    }

    // -------------------------------------------------------------------------
    // Replication (called while holding the lock)
    // -------------------------------------------------------------------------

    private void replicateToAll() {
        for (String peer : peerIds) {
            replicateToPeer(peer);
        }
        // Advance commitIndex based on matchIndex majority
        long prevCommit = commitIndex;
        advanceCommitIndex();
        // If commit advanced, send a follow-up heartbeat so followers learn the new
        // commitIndex immediately rather than waiting for the next proposal.
        if (commitIndex > prevCommit) {
            for (String peer : peerIds) {
                notifyCommit(peer);
            }
        }
        triggerApply();
    }

    private void notifyCommit(String peerId) {
        try {
            transport.appendEntries(peerId,
                    new AppendEntriesRequest(currentTerm, nodeId,
                            log.lastIndex(), log.lastTerm(), List.of(), commitIndex));
        } catch (RaftTransportException ignored) {}
    }

    private void replicateToPeer(String peerId) {
        long ni       = nextIndex.getOrDefault(peerId, 1L);
        long prevIdx  = ni - 1;
        long prevTerm = log.containsMatchingEntry(prevIdx, log.getEntry(prevIdx).term())
                ? log.getEntry(prevIdx).term() : 0;
        List<LogEntry> entries = log.entriesAfter(prevIdx);

        AppendEntriesResponse resp;
        try {
            resp = transport.appendEntries(peerId,
                    new AppendEntriesRequest(currentTerm, nodeId, prevIdx, prevTerm, entries, commitIndex));
        } catch (RaftTransportException e) {
            return; // peer unreachable — retry on next heartbeat
        }

        if (resp.term() > currentTerm) {
            stepDown(resp.term());
            return;
        }
        if (resp.success()) {
            matchIndex.put(peerId, resp.matchIndex());
            nextIndex.put(peerId,  resp.matchIndex() + 1);
        } else {
            // Decrement nextIndex and retry (simple back-off)
            nextIndex.merge(peerId, -1L, Long::sum);
            if (nextIndex.getOrDefault(peerId, 1L) < 1) nextIndex.put(peerId, 1L);
        }
    }

    private void advanceCommitIndex() {
        // Find the highest index N such that log[N].term == currentTerm
        // and a majority of matchIndex[i] >= N.
        for (long n = log.lastIndex(); n > commitIndex; n--) {
            if (log.getEntry(n).term() != currentTerm) continue;
            long count = 1; // leader itself
            for (long mi : matchIndex.values()) {
                if (mi >= n) count++;
            }
            if (count > (peerIds.size() + 1) / 2) {
                commitIndex = n;
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // State machine application (Phase 44)
    // -------------------------------------------------------------------------

    private void triggerApply() {
        RaftStateMachine sm = stateMachine;
        if (sm == null) return;
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.getEntry(lastApplied);
            sm.apply(entry);
            appliedCount.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Revert to follower with a new (higher) term, clearing votedFor. */
    private void stepDown(long newTerm) {
        currentTerm = newTerm;
        role        = RaftRole.FOLLOWER;
        votedFor    = null;
        resetElectionTimer();
    }

    private void resetElectionTimer() {
        electionDeadlineMs = nextDeadline();
    }

    private long nextDeadline() {
        int window = ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS;
        return System.currentTimeMillis() + ELECTION_TIMEOUT_MIN_MS + rng.nextInt(window);
    }

    /**
     * Raft log up-to-date comparison (§5.4.1):
     *   more up-to-date = higher last term, or same term with higher last index.
     */
    private boolean isCandidateLogUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long myLastTerm  = log.lastTerm();
        long myLastIndex = log.lastIndex();
        if (candidateLastTerm != myLastTerm) return candidateLastTerm > myLastTerm;
        return candidateLastIndex >= myLastIndex;
    }
}
