package com.nebulakv;

import com.nebulakv.admin.AdminServer;
import com.nebulakv.cluster.*;
import com.nebulakv.metrics.MetricsRegistry;
import com.nebulakv.network.KVServer;
import com.nebulakv.store.InMemoryKeyValueStore;

import java.io.IOException;

/**
 * NebulaKV entry point.
 *
 * Reads configuration from environment variables:
 *   NODE_ID     — unique node identifier (default: node1)
 *   HOST        — bind address (default: localhost)
 *   KV_PORT     — KV protocol port (default: 7777)
 *   ADMIN_PORT  — HTTP admin port (default: 7778)
 *   SEEDS       — comma-separated host:port pairs of seed nodes to join
 *
 * Startup sequence:
 *   1. Initialize in-memory store and metrics.
 *   2. Register this node in the membership manager.
 *   3. Start the KV protocol server.
 *   4. Start the admin HTTP server.
 *   5. Start the failure detector.
 *   6. Register seed nodes in membership (gossip handles propagation in Phase 22+).
 */
public final class NebulaKVServer {

    public static void main(String[] args) throws IOException {
        String nodeId    = env("NODE_ID",    "node1");
        String host      = env("HOST",       "localhost");
        int    kvPort    = Integer.parseInt(env("KV_PORT",    "7777"));
        int    adminPort = Integer.parseInt(env("ADMIN_PORT", "7778"));
        String seeds     = env("SEEDS",      "");

        System.out.println("[NebulaKV] Starting node=" + nodeId
                + " kv=" + host + ":" + kvPort
                + " admin=" + host + ":" + adminPort);

        InMemoryKeyValueStore store  = new InMemoryKeyValueStore();
        MetricsRegistry       metrics = new MetricsRegistry();
        MembershipManager     membership = new MembershipManager();

        // Register self.
        membership.join(ClusterNode.active(nodeId, host, kvPort));

        // KV server
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        KVServer kvServer = new KVServer(store, kvPort, threads);
        kvServer.start();

        // Admin server
        AdminServer adminServer = new AdminServer(membership, metrics, adminPort);
        adminServer.start();

        // Failure detector
        FailureDetector detector = new FailureDetector(membership);
        detector.start();
        detector.registerNode(nodeId);

        // Seed nodes (simplified: just register them as ACTIVE for cluster awareness)
        if (!seeds.isBlank()) {
            for (String seed : seeds.split(",")) {
                String[] parts = seed.trim().split(":");
                if (parts.length == 2) {
                    try {
                        String seedId   = parts[0];
                        int    seedPort = Integer.parseInt(parts[1]);
                        membership.join(ClusterNode.active(seedId, parts[0], seedPort));
                        detector.registerNode(seedId);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        System.out.println("[NebulaKV] Node " + nodeId + " is UP");

        // Park the main thread.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[NebulaKV] Shutting down " + nodeId);
            detector.stop();
            try { kvServer.close(); } catch (java.io.IOException ignored) {}
            adminServer.stop();
        }));

        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
