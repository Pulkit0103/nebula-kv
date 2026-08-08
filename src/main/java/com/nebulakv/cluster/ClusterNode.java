package com.nebulakv.cluster;

/**
 * Identity record for a cluster node.
 *
 * nodeId   — stable, unique identifier (UUID or hostname:port string)
 * host     — hostname or IP
 * port     — TCP port for KVServer
 * status   — lifecycle state
 */
public record ClusterNode(String nodeId, String host, int port, com.nebulakv.core.NodeStatus status) {

    public static ClusterNode active(String nodeId, String host, int port) {
        return new ClusterNode(nodeId, host, port, com.nebulakv.core.NodeStatus.ACTIVE);
    }

    public String address() {
        return host + ":" + port;
    }
}
