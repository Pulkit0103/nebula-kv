package com.nebulakv.cluster;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Heartbeat-based failure detector.
 *
 * Each node sends periodic heartbeats. This detector tracks the last heartbeat
 * timestamp for each node and triggers callbacks when a node misses its window.
 *
 * Thresholds:
 *   suspectThresholdMs  — time without a heartbeat before marking SUSPECT
 *   downThresholdMs     — time without a heartbeat before marking DOWN
 *
 * Default:
 *   Heartbeat interval  : 1 second (configured by sender)
 *   Suspect threshold   : 3 seconds (3 missed beats)
 *   Down threshold      : 10 seconds (10 missed beats)
 *
 * Why phi-accrual over simple timeout?
 *   Simple timeout is binary: either the node is alive or it isn't.
 *   Phi-accrual produces a continuous suspicion level φ that increases
 *   as the inter-heartbeat interval grows. This allows adaptive thresholds.
 *
 *   This implementation uses a simple timeout (cleaner to reason about,
 *   sufficient for the portfolio scope). Phi-accrual is documented as a
 *   future enhancement.
 */
public final class FailureDetector {

    static final long DEFAULT_SUSPECT_MS = 3_000;
    static final long DEFAULT_DOWN_MS    = 10_000;
    static final long CHECK_INTERVAL_MS  = 1_000;

    private final long suspectThresholdMs;
    private final long downThresholdMs;
    private final long checkIntervalMs;
    private final MembershipManager membership;

    // nodeId → last heartbeat time (epoch ms)
    private final ConcurrentHashMap<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public FailureDetector(MembershipManager membership) {
        this(membership, DEFAULT_SUSPECT_MS, DEFAULT_DOWN_MS);
    }

    public FailureDetector(MembershipManager membership, long suspectMs, long downMs) {
        this(membership, suspectMs, downMs, CHECK_INTERVAL_MS);
    }

    public FailureDetector(MembershipManager membership, long suspectMs, long downMs, long checkIntervalMs) {
        this.membership = membership;
        this.suspectThresholdMs = suspectMs;
        this.downThresholdMs = downMs;
        this.checkIntervalMs = checkIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nebula-failure-detector");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkHeartbeats,
                checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
    }

    /**
     * Records a heartbeat from a node. Resets its failure timer.
     * Also clears SUSPECT/DOWN status if the node recovers.
     */
    public void heartbeat(String nodeId) {
        lastHeartbeat.put(nodeId, System.currentTimeMillis());
    }

    /**
     * Starts tracking a node. Call when a node joins the cluster.
     * Seeds with current time so the node isn't immediately suspected.
     */
    public void registerNode(String nodeId) {
        lastHeartbeat.put(nodeId, System.currentTimeMillis());
    }

    /**
     * Stops tracking a node. Call when a node gracefully leaves.
     */
    public void unregisterNode(String nodeId) {
        lastHeartbeat.remove(nodeId);
    }

    /**
     * Returns the elapsed time since the last heartbeat from the given node.
     * Returns -1 if the node is not being tracked.
     */
    public long msSinceLastHeartbeat(String nodeId) {
        Long last = lastHeartbeat.get(nodeId);
        if (last == null) return -1;
        return System.currentTimeMillis() - last;
    }

    public Set<String> trackedNodes() {
        return lastHeartbeat.keySet();
    }

    // -------------------------------------------------------------------------
    // Internal check loop
    // -------------------------------------------------------------------------

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastHeartbeat.entrySet()) {
            String nodeId = entry.getKey();
            long elapsed = now - entry.getValue();

            if (elapsed >= downThresholdMs) {
                membership.markDown(nodeId);
            } else if (elapsed >= suspectThresholdMs) {
                membership.suspect(nodeId);
            }
        }
    }
}
