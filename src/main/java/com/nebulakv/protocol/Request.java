package com.nebulakv.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable command request.
 *
 * Wire format (big-endian):
 *
 *   [1 byte  : opcode      ]
 *   [4 bytes : key length  ]
 *   [N bytes : key (UTF-8) ]
 *   [4 bytes : value length] (0 for GET/DELETE/EXISTS)
 *   [M bytes : value (UTF-8)]
 *
 * No Java serialization. No JSON. Raw binary for minimal overhead.
 */
public final class Request {

    private final CommandType command;
    private final String key;
    private final String value; // null for GET / DELETE / EXISTS

    private Request(CommandType command, String key, String value) {
        this.command = Objects.requireNonNull(command, "command");
        this.key = Objects.requireNonNull(key, "key");
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static Request put(String key, String value) {
        Objects.requireNonNull(value, "value");
        return new Request(CommandType.PUT, key, value);
    }

    public static Request get(String key) {
        return new Request(CommandType.GET, key, null);
    }

    public static Request delete(String key) {
        return new Request(CommandType.DELETE, key, null);
    }

    public static Request exists(String key) {
        return new Request(CommandType.EXISTS, key, null);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * Encodes this request to a new heap ByteBuffer (ready to read from position 0).
     */
    public ByteBuffer encode() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (value != null) ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];

        int capacity = 1 + 4 + keyBytes.length + 4 + valueBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.put(command.opcode());
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);
        buf.flip();
        return buf;
    }

    /**
     * Decodes a Request from a ByteBuffer positioned at the start of a frame.
     * The buffer must contain a complete frame.
     */
    public static Request decode(ByteBuffer buf) {
        byte opcode = buf.get();
        CommandType cmd = CommandType.fromOpcode(opcode);

        int keyLen = buf.getInt();
        if (keyLen <= 0) throw new ProtocolException("key length must be positive, got " + keyLen);
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        int valueLen = buf.getInt();
        String value = null;
        if (valueLen > 0) {
            byte[] valueBytes = new byte[valueLen];
            buf.get(valueBytes);
            value = new String(valueBytes, StandardCharsets.UTF_8);
        }

        if (cmd == CommandType.PUT && value == null) {
            throw new ProtocolException("PUT request must include a value");
        }

        return new Request(cmd, key, value);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public CommandType command() { return command; }
    public String key()         { return key; }
    public Optional<String> value() { return Optional.ofNullable(value); }

    @Override
    public String toString() {
        return "Request{" + command + " key='" + key + "'" +
               (value != null ? " value='" + value + "'" : "") + "}";
    }
}
