package com.nebulakv.cluster;

/**
 * Quorum parameters for a NebulaKV cluster.
 *
 * N — replication factor (number of replicas per key)
 * W — write quorum (minimum replicas that must ACK before the write is considered durable)
 * R — read quorum (minimum replicas that must respond before returning a value)
 *
 * Strong consistency requires: R + W > N
 *
 * Default: N=3, W=2, R=2  →  R+W=4 > N=3  →  strong consistency
 */
public record QuorumConfig(int n, int w, int r) {

    public static final QuorumConfig DEFAULT = new QuorumConfig(3, 2, 2);
    public static final QuorumConfig QUORUM_1 = new QuorumConfig(1, 1, 1);

    public QuorumConfig {
        if (n < 1) throw new IllegalArgumentException("N must be >= 1");
        if (w < 1 || w > n) throw new IllegalArgumentException("W must be in [1, N]");
        if (r < 1 || r > n) throw new IllegalArgumentException("R must be in [1, N]");
    }

    public boolean isStronglyConsistent() {
        return r + w > n;
    }
}
