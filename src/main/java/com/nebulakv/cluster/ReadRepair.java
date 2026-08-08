package com.nebulakv.cluster;

import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import com.nebulakv.store.KeyValueStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-repair — background consistency reconciliation triggered during reads.
 *
 * When a coordinator reads from R replicas and receives conflicting values,
 * read repair writes the winning value back to the stale replicas. This
 * converges replicas toward consistency without requiring a background scan.
 *
 * Algorithm:
 *   1. Coordinator collects versioned responses from R replicas.
 *   2. ConflictResolver picks the winner (highest sequence number).
 *   3. Any replica whose value differs from the winner is patched.
 *
 * This is "synchronous" read repair (applied before the coordinator replies).
 * Asynchronous read repair (fire-and-forget after replying) is a future option.
 *
 * Scope: in-process stores used as stand-ins for real RPC (portfolio only).
 */
public final class ReadRepair {

    private ReadRepair() {}

    /**
     * Inspects replica responses and writes the winning value back to stale replicas.
     *
     * @param key       the key that was read
     * @param responses map of nodeId → versioned value returned by each replica (absent = key not found)
     * @param stores    map of nodeId → local store (stand-in for RPC in portfolio scope)
     * @return the winning value that was used for repair (empty only if all replicas had no value)
     */
    public static Optional<VersionedValue> repair(
            String key,
            Map<String, Optional<VersionedValue>> responses,
            Map<String, KeyValueStore> stores) {

        List<VersionedValue> values = responses.values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        Optional<VersionedValue> winner = ConflictResolver.resolve(values);

        if (winner.isEmpty()) {
            return Optional.empty();
        }

        VersionedValue winning = winner.get();

        for (Map.Entry<String, Optional<VersionedValue>> entry : responses.entrySet()) {
            String nodeId = entry.getKey();
            Optional<VersionedValue> replica = entry.getValue();

            boolean stale = replica.isEmpty()
                    || replica.get().sequenceNumber() < winning.sequenceNumber();

            if (!stale) continue;

            KeyValueStore store = stores.get(nodeId);
            if (store == null) continue;

            if (winning.tombstone()) {
                store.delete(key);
            } else {
                store.put(key, winning.value());
            }
        }

        return winner;
    }
}
