package com.nebulakv.raft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RaftLog — append-only Raft log")
class RaftLogTest {

    private RaftLog log;

    @BeforeEach
    void fresh() {
        log = new RaftLog();
    }

    @Test
    @DisplayName("empty log has lastIndex=0 and lastTerm=0 (sentinel)")
    void emptyLog() {
        assertEquals(0, log.lastIndex());
        assertEquals(0, log.lastTerm());
        assertEquals(1, log.size()); // sentinel only
    }

    @Test
    @DisplayName("append assigns sequential indexes starting at 1")
    void appendSequential() {
        long i1 = log.append(1, new RaftCommand.Put("a", "1"));
        long i2 = log.append(1, new RaftCommand.Put("b", "2"));
        long i3 = log.append(2, new RaftCommand.Delete("a"));

        assertEquals(1, i1);
        assertEquals(2, i2);
        assertEquals(3, i3);
        assertEquals(3, log.lastIndex());
        assertEquals(2, log.lastTerm());
    }

    @Test
    @DisplayName("getEntry retrieves correct entry")
    void getEntry() {
        log.append(1, new RaftCommand.Put("k", "v"));
        LogEntry e = log.getEntry(1);
        assertEquals(1, e.index());
        assertEquals(1, e.term());
        assertInstanceOf(RaftCommand.Put.class, e.command());
        assertEquals("k", ((RaftCommand.Put) e.command()).key());
    }

    @Test
    @DisplayName("getEntry(0) returns sentinel")
    void getSentinel() {
        LogEntry s = log.getEntry(0);
        assertEquals(LogEntry.SENTINEL, s);
    }

    @Test
    @DisplayName("getEntry out of bounds throws")
    void getEntryOutOfBounds() {
        assertThrows(IndexOutOfBoundsException.class, () -> log.getEntry(99));
    }

    @Test
    @DisplayName("containsMatchingEntry returns true for existing index+term")
    void containsMatch() {
        log.append(3, new RaftCommand.NoOp());
        assertTrue(log.containsMatchingEntry(1, 3));
        assertFalse(log.containsMatchingEntry(1, 2)); // wrong term
        assertFalse(log.containsMatchingEntry(5, 3)); // out of range
    }

    @Test
    @DisplayName("containsMatchingEntry(0, any) is always true (sentinel)")
    void sentinelAlwaysMatches() {
        assertTrue(log.containsMatchingEntry(0, 0));
        assertTrue(log.containsMatchingEntry(0, 99));
    }

    @Test
    @DisplayName("truncateSuffix removes entries from given index onward")
    void truncate() {
        log.append(1, new RaftCommand.Put("a", "1"));
        log.append(1, new RaftCommand.Put("b", "2"));
        log.append(2, new RaftCommand.Put("c", "3"));

        log.truncateSuffix(2); // remove entries 2 and 3

        assertEquals(1, log.lastIndex());
        assertEquals(1, log.lastTerm());
    }

    @Test
    @DisplayName("truncateSuffix beyond lastIndex is a no-op")
    void truncateBeyondEnd() {
        log.append(1, new RaftCommand.Put("x", "y"));
        log.truncateSuffix(99); // nothing to remove
        assertEquals(1, log.lastIndex());
    }

    @Test
    @DisplayName("entriesAfter returns all entries after given index")
    void entriesAfter() {
        log.append(1, new RaftCommand.Put("a", "1"));
        log.append(1, new RaftCommand.Put("b", "2"));
        log.append(2, new RaftCommand.Put("c", "3"));

        List<LogEntry> after1 = log.entriesAfter(1);
        assertEquals(2, after1.size());
        assertEquals(2, after1.get(0).index());
        assertEquals(3, after1.get(1).index());
    }

    @Test
    @DisplayName("entriesAfter(lastIndex) returns empty list")
    void entriesAfterLast() {
        log.append(1, new RaftCommand.NoOp());
        assertTrue(log.entriesAfter(1).isEmpty());
    }

    @Test
    @DisplayName("appendEntry requires sequential index")
    void appendEntryWrongIndex() {
        assertThrows(IllegalArgumentException.class,
            () -> log.appendEntry(new LogEntry(5, 1, new RaftCommand.NoOp())));
    }

    @Test
    @DisplayName("slice returns correct half-open range")
    void slice() {
        for (int i = 0; i < 5; i++) log.append(1, new RaftCommand.Put("k" + i, "v" + i));
        List<LogEntry> s = log.slice(2, 4);
        assertEquals(2, s.size());
        assertEquals(2, s.get(0).index());
        assertEquals(3, s.get(1).index());
    }
}
