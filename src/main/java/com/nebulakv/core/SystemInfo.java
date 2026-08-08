package com.nebulakv.core;

/**
 * Immutable snapshot of JVM and OS metadata collected at startup.
 */
public record SystemInfo(
        String javaVersion,
        String osName,
        int availableProcessors,
        long maxHeapMb
) {
    public static SystemInfo collect() {
        Runtime rt = Runtime.getRuntime();
        return new SystemInfo(
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                rt.availableProcessors(),
                rt.maxMemory() / (1024 * 1024)
        );
    }
}
