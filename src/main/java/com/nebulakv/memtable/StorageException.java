package com.nebulakv.memtable;

/** Unchecked wrapper for storage-layer IOExceptions. */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
