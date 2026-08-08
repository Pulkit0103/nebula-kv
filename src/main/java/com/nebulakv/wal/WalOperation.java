package com.nebulakv.wal;

/** Operations that can appear in the Write-Ahead Log. */
public enum WalOperation {
    PUT,
    DELETE
}
