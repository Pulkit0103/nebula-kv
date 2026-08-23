package com.nebulakv.raft;

/**
 * Commands that can be committed through the Raft log and applied to the state machine.
 *
 * Sealed so the compiler enforces exhaustive handling in switch expressions.
 */
public sealed interface RaftCommand permits RaftCommand.Put, RaftCommand.Delete, RaftCommand.NoOp {

    /** Store or overwrite a key-value pair. */
    record Put(String key, String value) implements RaftCommand {}

    /** Remove a key (tombstone). */
    record Delete(String key) implements RaftCommand {}

    /** Leader no-op entry appended on election to commit prior-term entries. */
    record NoOp() implements RaftCommand {}
}
