package com.nebulakv.store;

import java.util.Optional;

/**
 * Contract for the NebulaKV storage backend.
 *
 * Implementations must be thread-safe. This interface defines the four
 * fundamental operations. Later phases will add sequence numbers, TTLs,
 * and iteration.
 */
public interface KeyValueStore {

    /**
     * Stores or replaces the value for the given key.
     *
     * @param key   non-null, non-blank
     * @param value non-null
     */
    void put(String key, String value);

    /**
     * Returns the current value for the key, or empty if not present or deleted.
     */
    Optional<String> get(String key);

    /**
     * Marks the key as deleted. A subsequent get returns empty.
     * Deleting a non-existent key is a no-op.
     */
    void delete(String key);

    /**
     * Returns true if the key is present and not deleted.
     */
    boolean exists(String key);

    /**
     * Returns the number of live (non-deleted) entries.
     */
    long size();
}
