package com.nebulakv.raft;

/**
 * Key-value store backed by a Raft log.
 *
 * Writes go through the leader's propose() path for linearisable durability.
 * Reads are served directly from the local state machine — stale reads are
 * possible on followers; for strong consistency callers should only read from
 * the leader (enforced in Phase 45 via leader redirect).
 */
public final class RaftKVStore {

    private final RaftNode node;
    private final KVStateMachine stateMachine;

    public RaftKVStore(RaftNode node) {
        this.node         = node;
        this.stateMachine = new KVStateMachine();
        node.setStateMachine(stateMachine);
    }

    /**
     * Propose a PUT to the Raft cluster and wait for it to be applied locally.
     * Blocks until the entry is committed and applied on this node.
     *
     * @throws IllegalStateException if this node is not the leader
     */
    public void put(String key, String value) throws InterruptedException {
        long index = node.propose(new RaftCommand.Put(key, value));
        awaitApplied(index);
    }

    /**
     * Propose a DELETE and wait for application.
     *
     * @throws IllegalStateException if this node is not the leader
     */
    public void delete(String key) throws InterruptedException {
        long index = node.propose(new RaftCommand.Delete(key));
        awaitApplied(index);
    }

    /** Read from the local state machine (may be stale on followers). */
    public String get(String key) {
        return stateMachine.get(key);
    }

    public boolean containsKey(String key) {
        return stateMachine.containsKey(key);
    }

    public RaftNode node() { return node; }

    // -------------------------------------------------------------------------

    /** Spin-wait until lastApplied >= index (expected to complete quickly). */
    private void awaitApplied(long index) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (node.lastApplied() < index) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Timed out waiting for index " + index + " to be applied");
            }
            Thread.sleep(5);
        }
    }
}
