package com.nebulakv.wal;

/** Thrown when a WAL entry fails its checksum verification. */
public final class WalCorruptionException extends RuntimeException {

    public WalCorruptionException(String message) {
        super(message);
    }
}
