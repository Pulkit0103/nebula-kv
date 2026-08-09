package com.nebulakv.protocol;

/**
 * Status codes in the response frame header.
 *
 * 1-byte value. Stable once assigned.
 */
public enum ResponseStatus {

    OK(0x00),
    NOT_FOUND(0x01),
    ERROR(0x02),
    INVALID_REQUEST(0x03);

    private final byte code;

    ResponseStatus(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static ResponseStatus fromCode(byte code) {
        for (ResponseStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown response code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
