package com.nebulakv.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Redis Serialisation Protocol v2 (RESP2) inline and array formats.
 *
 * Supported input types:
 *   *N\r\n        — array of N bulk strings  (redis-cli sends this)
 *   $N\r\n        — bulk string of N bytes
 *   +<str>\r\n    — simple string (server-to-client; parsed for completeness)
 *   -<str>\r\n    — error string
 *   :<n>\r\n      — integer
 *
 * The result of parsing is a {@link RespValue} sealed type.
 */
public final class RespParser {

    private RespParser() {}

    /**
     * Parses one complete RESP value from the given bytes.
     * Throws {@link RespException} on malformed input.
     */
    public static RespValue parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new RespException("empty input");
        }
        return parseAt(data, 0).value();
    }

    // -- sealed result type --------------------------------------------------

    public sealed interface RespValue
            permits RespValue.BulkString, RespValue.SimpleString,
                    RespValue.ErrorString, RespValue.Integer, RespValue.Array,
                    RespValue.Null {

        record BulkString(String value)   implements RespValue {}
        record SimpleString(String value) implements RespValue {}
        record ErrorString(String value)  implements RespValue {}
        record Integer(long value)        implements RespValue {}
        record Array(List<RespValue> elements) implements RespValue {}
        record Null()                     implements RespValue {}
    }

    // -- internals -----------------------------------------------------------

    private record ParseResult(RespValue value, int nextOffset) {}

    private static ParseResult parseAt(byte[] data, int offset) {
        if (offset >= data.length) throw new RespException("unexpected end of input");
        char prefix = (char) data[offset];
        return switch (prefix) {
            case '*' -> parseArray(data, offset);
            case '$' -> parseBulkString(data, offset);
            case '+' -> parseSimpleString(data, offset);
            case '-' -> parseErrorString(data, offset);
            case ':' -> parseInteger(data, offset);
            default  -> throw new RespException("unknown RESP prefix: " + prefix);
        };
    }

    private static ParseResult parseArray(byte[] data, int offset) {
        int crlfPos = findCRLF(data, offset + 1);
        int count   = parseInt(data, offset + 1, crlfPos);
        if (count < 0) return new ParseResult(new RespValue.Null(), crlfPos + 2);

        int pos = crlfPos + 2;
        List<RespValue> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ParseResult pr = parseAt(data, pos);
            elements.add(pr.value());
            pos = pr.nextOffset();
        }
        return new ParseResult(new RespValue.Array(List.copyOf(elements)), pos);
    }

    private static ParseResult parseBulkString(byte[] data, int offset) {
        int crlfPos = findCRLF(data, offset + 1);
        int len     = parseInt(data, offset + 1, crlfPos);
        if (len < 0) return new ParseResult(new RespValue.Null(), crlfPos + 2);

        int start = crlfPos + 2;
        if (start + len > data.length) throw new RespException("bulk string truncated");
        String value = new String(data, start, len, java.nio.charset.StandardCharsets.UTF_8);
        return new ParseResult(new RespValue.BulkString(value), start + len + 2);
    }

    private static ParseResult parseSimpleString(byte[] data, int offset) {
        int crlfPos = findCRLF(data, offset + 1);
        String value = new String(data, offset + 1, crlfPos - offset - 1,
                java.nio.charset.StandardCharsets.UTF_8);
        return new ParseResult(new RespValue.SimpleString(value), crlfPos + 2);
    }

    private static ParseResult parseErrorString(byte[] data, int offset) {
        int crlfPos = findCRLF(data, offset + 1);
        String value = new String(data, offset + 1, crlfPos - offset - 1,
                java.nio.charset.StandardCharsets.UTF_8);
        return new ParseResult(new RespValue.ErrorString(value), crlfPos + 2);
    }

    private static ParseResult parseInteger(byte[] data, int offset) {
        int crlfPos = findCRLF(data, offset + 1);
        long value  = Long.parseLong(new String(data, offset + 1, crlfPos - offset - 1,
                java.nio.charset.StandardCharsets.US_ASCII));
        return new ParseResult(new RespValue.Integer(value), crlfPos + 2);
    }

    private static int findCRLF(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') return i;
        }
        throw new RespException("CRLF not found starting at offset " + from);
    }

    private static int parseInt(byte[] data, int from, int to) {
        return java.lang.Integer.parseInt(
                new String(data, from, to - from, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
