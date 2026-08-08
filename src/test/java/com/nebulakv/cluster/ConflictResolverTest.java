package com.nebulakv.cluster;

import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConflictResolver — sequence-number-based resolution")
class ConflictResolverTest {

    @Test
    @DisplayName("highest sequence number wins")
    void highestSeqWins() {
        VersionedValue old   = VersionedValue.live("old-value", 5L);
        VersionedValue newer = VersionedValue.live("new-value", 10L);
        VersionedValue oldest= VersionedValue.live("oldest",    1L);

        Optional<VersionedValue> winner = ConflictResolver.resolve(List.of(old, newest, oldest));
        assertTrue(winner.isPresent());
        assertEquals("new-value", winner.get().value());
    }

    @Test
    @DisplayName("single value always wins")
    void singleValueWins() {
        VersionedValue v = VersionedValue.live("only", 42L);
        assertEquals(Optional.of(v), ConflictResolver.resolve(List.of(v)));
    }

    @Test
    @DisplayName("empty collection returns empty")
    void emptyCollectionReturnsEmpty() {
        assertEquals(Optional.empty(), ConflictResolver.resolve(List.of()));
    }

    @Test
    @DisplayName("tombstone with higher seq wins over live value")
    void tombstoneWithHigherSeqWins() {
        VersionedValue live  = VersionedValue.live("value", 5L);
        VersionedValue tomb  = VersionedValue.tombstone(20L);

        Optional<VersionedValue> winner = ConflictResolver.resolve(List.of(live, tomb));
        assertTrue(winner.isPresent());
        assertTrue(winner.get().tombstone(), "Tombstone with higher seq must win");
    }

    @Test
    @DisplayName("resolveTwo returns higher seq")
    void resolveTwoReturnsHigherSeq() {
        VersionedValue a = VersionedValue.live("a", 3L);
        VersionedValue b = VersionedValue.live("b", 7L);

        assertEquals(b, ConflictResolver.resolveTwo(a, b));
        assertEquals(b, ConflictResolver.resolveTwo(b, a));
    }

    @Test
    @DisplayName("equal sequence numbers use deterministic tie-break (left wins)")
    void equalSeqTieBreak() {
        VersionedValue a = VersionedValue.live("a", 5L);
        VersionedValue b = VersionedValue.live("b", 5L);

        assertEquals(a, ConflictResolver.resolveTwo(a, b));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    // Using 'newest' in the test — should be 'newer'. Fix the field name reference above.
    private static final VersionedValue newest = VersionedValue.live("new-value", 10L);
}
