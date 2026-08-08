package com.nebulakv.memtable;

import java.nio.charset.StandardCharsets;

/**
 * A single record held in the MemTable.
 *
 * tombstone=true means the key was deleted. Tombstones must propagate to SSTables
 * so that compaction can suppress older versions of the key across files.
 *
 * estimatedBytes is used for flush-threshold tracking. It does not need to be
 * exact — a slight overestimate is acceptable and safer than an underestimate.
 */
public record MemTableEntry(String value, long sequenceNumber, boolean tombstone) {

    /** Estimated heap bytes for this entry (key not included — tracked by MemTable). */
    public int estimatedValueBytes() {
        if (value == null) return 0;
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static MemTableEntry live(String value, long sequenceNumber) {
        return new MemTableEntry(value, sequenceNumber, false);
    }

    public static MemTableEntry tombstone(long sequenceNumber) {
        return new MemTableEntry(null, sequenceNumber, true);
    }
}
