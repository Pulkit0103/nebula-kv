package com.nebulakv.core;

/**
 * Lifecycle states for a NebulaKV cluster node.
 *
 * STANDALONE  — single-node mode, no cluster membership yet (bootstrap).
 * JOINING     — in the process of registering with the cluster.
 * ACTIVE      — healthy member of the ring.
 * SUSPECT     — missed heartbeats; under observation.
 * DOWN        — confirmed unreachable.
 * LEAVING     — graceful decommission in progress.
 */
public enum NodeStatus {
    STANDALONE,
    JOINING,
    ACTIVE,
    SUSPECT,
    DOWN,
    LEAVING
}
