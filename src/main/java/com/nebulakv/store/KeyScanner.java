package com.nebulakv.store;

import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Key scanning over an InMemoryKeyValueStore.
 *
 * Two scan modes:
 *   scanPrefix(prefix)       — all keys that start with {@code prefix}, sorted
 *   scanRange(from, to)      — all keys in [from, to) lexicographic range, sorted
 *
 * Both return an immutable snapshot — mutations after the call are not reflected.
 * The underlying store's keySet() already returns a copy, so this is safe.
 *
 * Complexity: O(N) scan over all live keys. For large datasets the SSTable
 * sparse index (Phase 9) makes disk-based range scans much cheaper; this
 * implementation operates only on the in-memory tier.
 */
public final class KeyScanner {

    private final InMemoryKeyValueStore store;

    public KeyScanner(InMemoryKeyValueStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * Returns all keys that start with {@code prefix}, in ascending lexicographic order.
     * Empty prefix matches all keys.
     */
    public List<String> scanPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .toList();
    }

    /**
     * Returns all keys in the half-open range [{@code from}, {@code to}),
     * in ascending lexicographic order.
     *
     * @param from inclusive lower bound (empty string means start of keyspace)
     * @param to   exclusive upper bound (empty string means end of keyspace)
     */
    public List<String> scanRange(String from, String to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to,   "to must not be null");

        SortedMap<String, Boolean> sorted = new TreeMap<>();
        for (String key : store.keySet()) {
            sorted.put(key, Boolean.TRUE);
        }

        SortedMap<String, Boolean> sub;
        boolean openFrom = from.isEmpty();
        boolean openTo   = to.isEmpty();

        if (openFrom && openTo) {
            sub = sorted;
        } else if (openFrom) {
            sub = sorted.headMap(to);
        } else if (openTo) {
            sub = sorted.tailMap(from);
        } else {
            if (from.compareTo(to) >= 0) {
                throw new IllegalArgumentException(
                        "from must be strictly less than to, got from=" + from + " to=" + to);
            }
            sub = sorted.subMap(from, to);
        }

        return List.copyOf(sub.keySet());
    }

    /**
     * Returns the total number of live keys (convenience for callers that want
     * a count without fetching all keys).
     */
    public long count() {
        return store.size();
    }
}
