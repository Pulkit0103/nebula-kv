package com.nebulakv.cluster;

import com.nebulakv.store.KeyValueStore;

import java.util.*;

/**
 * Token ring rebalancer — moves keys when nodes join or leave.
 *
 * When a new node joins, some tokens that previously mapped to existing nodes
 * now map to the new node. Keys whose primary replica has changed must be
 * migrated (or at minimum, seeded) on the new node.
 *
 * When a node leaves, its tokens are removed from the ring and the keys that
 * mapped to it now map to the next clockwise node. Read repair and hinted
 * handoff handle the convergence, but an explicit rebalance seeds the data
 * faster.
 *
 * This implementation:
 *   - Walks all keys in the source stores.
 *   - For each key, resolves the new primary owner via the hash ring.
 *   - If the owner differs from the source, copies the key to the target store.
 *
 * Scope: in-process stores (no RPC). Designed to be called by the coordinator
 * after ring changes. Production systems pipeline this with anti-entropy scans.
 */
public final class Rebalancer {

    private final HashRing ring;

    public Rebalancer(HashRing ring) {
        this.ring = ring;
    }

    /**
     * Rebalances keys after a node join.
     *
     * Copies keys from existing nodes to {@code newNodeId} where the ring now
     * routes them to the new node.
     *
     * @param newNodeId  the node that just joined
     * @param newStore   the new node's local store (destination)
     * @param allStores  all node stores keyed by nodeId (sources)
     * @return number of keys migrated
     */
    public int rebalanceOnJoin(String newNodeId,
                                KeyValueStore newStore,
                                Map<String, KeyValueStore> allStores) {
        int migrated = 0;
        for (Map.Entry<String, KeyValueStore> entry : allStores.entrySet()) {
            String sourceNodeId = entry.getKey();
            if (sourceNodeId.equals(newNodeId)) continue;

            KeyValueStore source = entry.getValue();
            // We can't iterate a KeyValueStore directly — use the snapshot approach
            // by walking keys we know about. In practice this is backed by MemTable
            // or SSTable scan. For portfolio scope we cast to InMemoryKeyValueStore
            // if available; otherwise skip.
            if (!(source instanceof com.nebulakv.store.InMemoryKeyValueStore inMem)) continue;

            for (String key : inMem.keySet()) {
                String primaryOwner = ring.primaryNode(key).map(ClusterNode::nodeId).orElse(null);
                if (newNodeId.equals(primaryOwner)) {
                    Optional<String> val = source.get(key);
                    val.ifPresent(v -> newStore.put(key, v));
                    migrated++;
                }
            }
        }
        return migrated;
    }

    /**
     * Rebalances keys after a node leaves or is marked DOWN.
     *
     * Copies keys that were owned by {@code departedNodeId} to their new
     * primary owner according to the updated ring (departed node already removed).
     *
     * @param departedNodeId  the node that left
     * @param departedStore   the departed node's store (source)
     * @param allStores       remaining node stores keyed by nodeId (destinations)
     * @return number of keys migrated
     */
    public int rebalanceOnLeave(String departedNodeId,
                                 KeyValueStore departedStore,
                                 Map<String, KeyValueStore> allStores) {
        if (!(departedStore instanceof com.nebulakv.store.InMemoryKeyValueStore inMem)) return 0;

        int migrated = 0;
        for (String key : inMem.keySet()) {
            String newOwner = ring.primaryNode(key).map(ClusterNode::nodeId).orElse(null);
            if (newOwner == null || newOwner.equals(departedNodeId)) continue;

            KeyValueStore target = allStores.get(newOwner);
            if (target == null) continue;

            Optional<String> val = departedStore.get(key);
            val.ifPresent(v -> target.put(key, v));
            migrated++;
        }
        return migrated;
    }
}
