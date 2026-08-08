package com.nebulakv.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent hash ring with virtual nodes.
 *
 * Why consistent hashing?
 *   With N nodes and naive modulo hashing (key % N), adding or removing a node
 *   remaps ~N/(N+1) of all keys. With a consistent hash ring, only ~1/N of keys
 *   are remapped — critical for rebalancing efficiency in a live cluster.
 *
 * Why virtual nodes?
 *   Without vnodes, each physical node owns one contiguous arc of the ring.
 *   Uneven key distribution is common. With V vnodes per node, each physical node
 *   is represented by V points on the ring, producing more uniform distribution.
 *   Default: 150 vnodes per physical node.
 *
 * Token space: [0, Long.MAX_VALUE] (using MD5 hash, taking 64 bits)
 *
 * Thread safety: the ring is protected by a ReentrantReadWriteLock.
 * Reads (lookup) are concurrent; writes (add/remove) are exclusive.
 */
public final class HashRing {

    private static final int DEFAULT_VNODES = 150;

    private final int vnodeCount;
    // Sorted map: token → node. Using ConcurrentSkipListMap for lock-free reads.
    private final ConcurrentSkipListMap<Long, ClusterNode> ring = new ConcurrentSkipListMap<>();
    // nodeId → set of tokens (for removal).
    private final Map<String, Set<Long>> nodeTokens = new HashMap<>();
    private final Object writeLock = new Object();

    public HashRing() {
        this(DEFAULT_VNODES);
    }

    public HashRing(int vnodeCount) {
        this.vnodeCount = vnodeCount;
    }

    /**
     * Adds a node to the ring by placing vnodeCount virtual nodes.
     * Idempotent — adding the same node twice is a no-op.
     */
    public void addNode(ClusterNode node) {
        synchronized (writeLock) {
            if (nodeTokens.containsKey(node.nodeId())) return;
            Set<Long> tokens = new HashSet<>(vnodeCount);
            for (int i = 0; i < vnodeCount; i++) {
                long token = hash(node.nodeId() + "#vn" + i);
                ring.put(token, node);
                tokens.add(token);
            }
            nodeTokens.put(node.nodeId(), tokens);
        }
    }

    /**
     * Removes a node from the ring by deleting all its virtual nodes.
     * Safe to call if node is not present.
     */
    public void removeNode(String nodeId) {
        synchronized (writeLock) {
            Set<Long> tokens = nodeTokens.remove(nodeId);
            if (tokens == null) return;
            tokens.forEach(ring::remove);
        }
    }

    /**
     * Returns the primary node responsible for the given key.
     * Walks clockwise from the key's token until a live node is found.
     * Returns empty if the ring is empty.
     */
    public Optional<ClusterNode> primaryNode(String key) {
        if (ring.isEmpty()) return Optional.empty();
        long token = hash(key);
        Map.Entry<Long, ClusterNode> entry = ring.ceilingEntry(token);
        if (entry == null) entry = ring.firstEntry(); // wrap around
        return Optional.of(entry.getValue());
    }

    /**
     * Returns N distinct physical nodes responsible for the given key (for replication).
     * Walks clockwise, skipping duplicate physical nodes.
     * Returns fewer than N if fewer nodes are available.
     */
    public List<ClusterNode> replicaNodes(String key, int n) {
        if (ring.isEmpty()) return Collections.emptyList();

        List<ClusterNode> replicas = new ArrayList<>(n);
        Set<String> seen = new HashSet<>();
        long token = hash(key);

        // Start from the primary and walk clockwise.
        NavigableMap<Long, ClusterNode> tailMap = ring.tailMap(token, true);
        Iterator<ClusterNode> iter = tailMap.values().iterator();

        // If tail is exhausted, wrap around from the beginning.
        while (replicas.size() < n) {
            if (!iter.hasNext()) {
                iter = ring.values().iterator();
            }
            ClusterNode node = iter.next();
            if (seen.add(node.nodeId())) {
                replicas.add(node);
            }
            if (seen.size() == nodeTokens.size()) break; // all physical nodes visited
        }
        return replicas;
    }

    /**
     * Returns all physical nodes currently in the ring.
     */
    public Set<String> nodeIds() {
        synchronized (writeLock) {
            return Collections.unmodifiableSet(new HashSet<>(nodeTokens.keySet()));
        }
    }

    public int physicalNodeCount() {
        return nodeTokens.size();
    }

    public int totalTokenCount() {
        return ring.size();
    }

    // -------------------------------------------------------------------------
    // Hash function — MD5, taking the first 8 bytes as a long
    // -------------------------------------------------------------------------

    static long hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xFFL);
            }
            return h & Long.MAX_VALUE; // keep positive
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
