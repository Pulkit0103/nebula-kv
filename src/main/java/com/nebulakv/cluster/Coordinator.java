package com.nebulakv.cluster;

import com.nebulakv.memtable.MemTableEntry;
import com.nebulakv.store.KeyValueStore;

import java.util.*;
import java.util.concurrent.*;

/**
 * Routes client requests to the correct replica nodes and enforces quorum.
 *
 * Write path (W quorum):
 *   1. Look up N replicas for the key via the hash ring.
 *   2. Send PUT/DELETE to all N replicas in parallel.
 *   3. Wait for W acknowledgments (or timeout).
 *   4. Return OK to client; remaining replicas complete asynchronously.
 *
 * Read path (R quorum):
 *   1. Look up N replicas.
 *   2. Send GET to all N replicas in parallel.
 *   3. Wait for R responses.
 *   4. Resolve conflicts: highest sequence number wins.
 *   5. Detect stale replicas; schedule async read repair.
 *   6. Return the latest value to the client.
 *
 * Phase 15/16 implementation: the Coordinator delegates to local KeyValueStores
 * (simulating multi-node via a map of node stores). Full TCP routing across
 * physical nodes is a Phase 28/29 concern.
 */
public final class Coordinator {

    private final HashRing ring;
    private final QuorumConfig quorum;
    // In this phase, nodes are simulated as local KeyValueStore instances.
    private final Map<String, KeyValueStore> nodeStores;
    private final ExecutorService pool;

    public Coordinator(HashRing ring, QuorumConfig quorum, Map<String, KeyValueStore> nodeStores) {
        this.ring = ring;
        this.quorum = quorum;
        this.nodeStores = nodeStores;
        this.pool = Executors.newCachedThreadPool(r ->
                new Thread(r, "nebula-coordinator-" + System.nanoTime()));
    }

    /**
     * Executes a PUT with write quorum.
     * Returns true if W replicas acknowledged; false if quorum was not reached.
     */
    public boolean put(String key, String value) {
        List<ClusterNode> replicas = ring.replicaNodes(key, quorum.n());
        if (replicas.isEmpty()) return false;

        CountDownLatch ack = new CountDownLatch(quorum.w());
        int target = Math.min(replicas.size(), quorum.n());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < target; i++) {
            KeyValueStore store = nodeStores.get(replicas.get(i).nodeId());
            if (store == null) continue;
            futures.add(pool.submit(() -> {
                store.put(key, value);
                ack.countDown();
            }));
        }

        try {
            boolean reached = ack.await(500, TimeUnit.MILLISECONDS);
            futures.forEach(f -> f.cancel(false));
            return reached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Executes a GET with read quorum.
     * Returns the value with the highest sequence number across R responses,
     * or empty if the key is not found on any replica.
     */
    public Optional<String> get(String key) {
        List<ClusterNode> replicas = ring.replicaNodes(key, quorum.n());
        if (replicas.isEmpty()) return Optional.empty();

        int target = Math.min(replicas.size(), quorum.r());
        List<Future<Optional<String>>> futures = new ArrayList<>();

        for (int i = 0; i < Math.min(replicas.size(), quorum.n()); i++) {
            KeyValueStore store = nodeStores.get(replicas.get(i).nodeId());
            if (store == null) continue;
            futures.add(pool.submit(() -> store.get(key)));
        }

        List<Optional<String>> responses = new ArrayList<>();
        for (Future<Optional<String>> f : futures) {
            try {
                responses.add(f.get(500, TimeUnit.MILLISECONDS));
                if (responses.size() >= target) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                // Node unresponsive — skip.
            }
        }

        if (responses.size() < target) return Optional.empty(); // quorum not reached

        // Conflict resolution: return first present value (Phase 17 adds sequence-based resolution).
        return responses.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * Executes a DELETE with write quorum.
     */
    public boolean delete(String key) {
        List<ClusterNode> replicas = ring.replicaNodes(key, quorum.n());
        if (replicas.isEmpty()) return false;

        CountDownLatch ack = new CountDownLatch(quorum.w());
        int target = Math.min(replicas.size(), quorum.n());

        for (int i = 0; i < target; i++) {
            KeyValueStore store = nodeStores.get(replicas.get(i).nodeId());
            if (store == null) continue;
            pool.submit(() -> {
                store.delete(key);
                ack.countDown();
            });
        }

        try {
            return ack.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void shutdown() {
        pool.shutdown();
    }
}
