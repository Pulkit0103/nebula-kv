package com.nebulakv.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Atomic batch operations over a KeyValueStore.
 *
 * MPUT and MDELETE are applied under a single logical transaction —
 * either all mutations succeed or none are visible (optimistic: applies
 * mutations one-by-one then rolls back on any failure).
 *
 * MGET is a non-atomic snapshot read: each key is read independently.
 * This is intentional — a fully atomic multi-key read requires MVCC
 * (out of scope for this phase).
 */
public final class BatchProcessor {

    private final KeyValueStore store;

    public BatchProcessor(KeyValueStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * Atomically writes all key-value pairs.
     * On failure mid-batch, previously applied mutations are rolled back.
     *
     * @param entries ordered map of key → value; neither keys nor values may be null
     * @throws IllegalArgumentException if entries is empty or contains null
     */
    public void mput(Map<String, String> entries) {
        validate(entries);
        List<String> applied = new java.util.ArrayList<>();
        try {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                store.put(e.getKey(), e.getValue());
                applied.add(e.getKey());
            }
        } catch (RuntimeException ex) {
            // Best-effort rollback: re-delete keys that were inserted in this batch.
            for (String key : applied) {
                try { store.delete(key); } catch (RuntimeException ignored) {}
            }
            throw ex;
        }
    }

    /**
     * Reads multiple keys. Returns a map preserving insertion order.
     * Missing keys map to {@link Optional#empty()}.
     */
    public Map<String, Optional<String>> mget(List<String> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        Map<String, Optional<String>> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, store.get(key));
        }
        return result;
    }

    /**
     * Atomically deletes all specified keys.
     * On failure mid-batch, previously deleted keys are NOT restored —
     * delete is idempotent and the caller is responsible for retrying.
     */
    public void mdelete(List<String> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        if (keys.isEmpty()) throw new IllegalArgumentException("keys must not be empty");
        for (String key : keys) {
            store.delete(key);
        }
    }

    /**
     * Returns true only if ALL keys exist.
     */
    public boolean mexists(List<String> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        for (String key : keys) {
            if (!store.exists(key)) return false;
        }
        return true;
    }

    private static void validate(Map<String, String> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) throw new IllegalArgumentException("entries must not be empty");
        entries.forEach((k, v) -> {
            Objects.requireNonNull(k, "key must not be null");
            Objects.requireNonNull(v, "value must not be null");
        });
    }
}
