package com.nebulakv.checksum;

public final class ChecksumMismatchException extends RuntimeException {

    private final long expected;
    private final long actual;

    public ChecksumMismatchException(long expected, long actual) {
        super(String.format("Checksum mismatch: expected=%08x actual=%08x", expected, actual));
        this.expected = expected;
        this.actual   = actual;
    }

    public long expected() { return expected; }
    public long actual()   { return actual;   }
}
