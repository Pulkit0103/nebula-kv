package com.nebulakv;

import com.nebulakv.core.NodeInfo;
import com.nebulakv.core.SystemInfo;

/**
 * NebulaKV — entry point.
 *
 * Bootstrap phase: prints version/system info and exits cleanly.
 * Subsequent phases will replace this with a real server loop.
 */
public class NebulaKV {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        SystemInfo sysInfo = SystemInfo.collect();
        NodeInfo nodeInfo = NodeInfo.defaults();

        System.out.println("==========================================");
        System.out.println("  NebulaKV  v" + VERSION);
        System.out.println("  Distributed Key-Value Database");
        System.out.println("==========================================");
        System.out.println();
        System.out.printf("  Java     : %s%n", sysInfo.javaVersion());
        System.out.printf("  OS       : %s%n", sysInfo.osName());
        System.out.printf("  CPUs     : %d%n", sysInfo.availableProcessors());
        System.out.printf("  Heap max : %d MB%n", sysInfo.maxHeapMb());
        System.out.println();
        System.out.printf("  Node ID  : %s%n", nodeInfo.nodeId());
        System.out.printf("  Host     : %s%n", nodeInfo.host());
        System.out.printf("  Status   : %s%n", nodeInfo.status());
        System.out.println();
        System.out.println("  NebulaKV bootstrap complete.");
        System.out.println("  Waiting for next phase implementation...");
    }
}
