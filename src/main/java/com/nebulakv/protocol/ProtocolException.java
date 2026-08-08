package com.nebulakv.protocol;

/**
 * Thrown when a wire frame cannot be decoded due to invalid structure,
 * unknown opcodes, or length violations.
 */
public final class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
