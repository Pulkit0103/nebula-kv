package com.nebulakv.cluster;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Conflict resolution for concurrent writes to the same key across replicas.
 *
 * Strategy: highest sequence number wins.
 *
 * Why sequence numbers over wall-clock time?
 *   Wall-clock timestamps are unreliable in distributed systems:
 *   - NTP synchronization introduces ~100ms typical skew
 *   - Clocks can move backward after correction
 *   - Two nodes can assign the same timestamp to different writes
 *
 *   Sequence numbers are monotonically increasing within a node.
 *   When a write arrives at a replica, it includes the sequence number
 *   from the originating node. Comparison is unambiguous.
 *
 *   Limitation: sequence numbers only provide total order within a single node.
 *   For cross-node causality, Phase 17 documents vector clocks as a future option.
 *
 * A VersionedValue carries a value paired with a sequence number.
 */
public final class ConflictResolver {

    private ConflictResolver() {}

    /**
     * Returns the value with the highest sequence number.
     * Returns empty only if the input collection is empty.
     */
    public static Optional<VersionedValue> resolve(Collection<VersionedValue> versions) {
        return versions.stream()
                .max(Comparator.comparingLong(VersionedValue::sequenceNumber));
    }

    /**
     * Returns the latest of two versioned values.
     * If sequence numbers are equal, returns the left value (deterministic tie-break).
     */
    public static VersionedValue resolveTwo(VersionedValue a, VersionedValue b) {
        return (b.sequenceNumber() > a.sequenceNumber()) ? b : a;
    }

    public record VersionedValue(String value, long sequenceNumber, boolean tombstone) {

        public static VersionedValue live(String value, long seq) {
            return new VersionedValue(value, seq, false);
        }

        public static VersionedValue tombstone(long seq) {
            return new VersionedValue(null, seq, true);
        }
    }
}
