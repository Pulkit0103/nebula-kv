package com.nebulakv.snapshot;

public final class SnapshotCorruptionException extends RuntimeException {
    public SnapshotCorruptionException(String message) {
        super(message);
    }
}
