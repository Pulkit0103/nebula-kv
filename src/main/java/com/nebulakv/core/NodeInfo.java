package com.nebulakv.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Identity and status of this NebulaKV node.
 *
 * Phase 1 (bootstrap): a static defaults() factory is sufficient.
 * Later phases will hydrate this from cluster membership config.
 */
public record NodeInfo(String nodeId, String host, NodeStatus status) {

    public static NodeInfo defaults() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "localhost";
        }
        return new NodeInfo(UUID.randomUUID().toString(), host, NodeStatus.STANDALONE);
    }
}
