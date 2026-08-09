package com.nebulakv.cluster;

import com.nebulakv.store.KeyValueStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hinted handoff — temporary mutation storage for unavailable replicas.
 *
 * When a coordinator cannot reach a target replica, it stores a "hint":
 * the mutation paired with the intended destination. A background thread
 * periodically replays hints to recovered nodes and clears delivered ones.
 *
 * This keeps write availability high under transient failures: the
 * coordinator still reaches quorum by routing to a standby, and the
 * original replica catches up once it comes back online.
 *
 * Limitations (portfolio scope):
 *   - Hints are in-memory only; they are lost if the coordinator crashes.
 *   - Replay is best-effort: if the target remains down across a restart,
 *     the hint is gone. A production system would persist hints to disk.
 */
public final class HintedHandoff {

    static final long DEFAULT_REPLAY_INTERVAL_MS = 5_000;

    public enum Op { PUT, DELETE }

    public record Hint(String targetNodeId, Op op, String key, String value, long sequenceNumber) {
        public static Hint put(String targetNodeId, String key, String value, long seq) {
            return new Hint(targetNodeId, Op.PUT, key, value, seq);
        }

        public static Hint delete(String targetNodeId, String key, long seq) {
            return new Hint(targetNodeId, Op.DELETE, key, null, seq);
        }
    }

    // nodeId → pending hints for that node
    private final ConcurrentHashMap<String, List<Hint>> hints = new ConcurrentHashMap<>();

    // nodeId → local store (stand-in for real RPC in portfolio scope)
    private final ConcurrentHashMap<String, KeyValueStore> nodeStores = new ConcurrentHashMap<>();

    private final MembershipManager membership;
    private final ScheduledExecutorService scheduler;
    private final long replayIntervalMs;

    public HintedHandoff(MembershipManager membership) {
        this(membership, DEFAULT_REPLAY_INTERVAL_MS);
    }

    public HintedHandoff(MembershipManager membership, long replayIntervalMs) {
        this.membership = membership;
        this.replayIntervalMs = replayIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nebula-hint-replay");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::replayHints,
                replayIntervalMs, replayIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
    }

    /**
     * Registers the local store for a node so replay can deliver hints.
     * In a real implementation this would be an RPC stub.
     */
    public void registerStore(String nodeId, KeyValueStore store) {
        nodeStores.put(nodeId, store);
    }

    /** Store a hint for a mutation that could not reach {@code targetNodeId}. */
    public void storeHint(Hint hint) {
        hints.computeIfAbsent(hint.targetNodeId(), k -> new ArrayList<>()).add(hint);
    }

    /** Returns an unmodifiable snapshot of pending hints for the given node. */
    public List<Hint> pendingHints(String nodeId) {
        List<Hint> list = hints.get(nodeId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Returns the total number of pending hints across all nodes. */
    public int totalPendingHints() {
        return hints.values().stream().mapToInt(List::size).sum();
    }

    // -------------------------------------------------------------------------
    // Replay
    // -------------------------------------------------------------------------

    public void replayHints() {
        for (Map.Entry<String, List<Hint>> entry : hints.entrySet()) {
            String nodeId = entry.getKey();

            // Only replay if the node is ACTIVE again.
            if (!membership.statusOf(nodeId)
                    .map(s -> s == com.nebulakv.core.NodeStatus.ACTIVE)
                    .orElse(false)) {
                continue;
            }

            KeyValueStore store = nodeStores.get(nodeId);
            if (store == null) continue;

            List<Hint> pending = entry.getValue();
            List<Hint> delivered = new ArrayList<>();

            for (Hint hint : pending) {
                try {
                    if (hint.op() == Op.PUT) {
                        store.put(hint.key(), hint.value());
                    } else {
                        store.delete(hint.key());
                    }
                    delivered.add(hint);
                } catch (Exception ignored) {
                    // Leave undelivered hints in place for next replay cycle.
                }
            }

            pending.removeAll(delivered);
            if (pending.isEmpty()) {
                hints.remove(nodeId, pending);
            }
        }
    }
}
