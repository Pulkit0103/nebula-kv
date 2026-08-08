package com.nebulakv.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Immutable command response.
 *
 * Wire format (big-endian):
 *
 *   [1 byte  : status code  ]
 *   [4 bytes : payload length]
 *   [N bytes : payload (UTF-8)] (empty for DELETE/EXISTS-false/error-less OK)
 *
 * For EXISTS: payload is "1" (true) or "0" (false).
 * For GET:    payload is the value, or empty on NOT_FOUND.
 * For PUT/DELETE: payload is empty on OK.
 */
public final class Response {

    private final ResponseStatus status;
    private final String payload; // never null, may be empty

    private Response(ResponseStatus status, String payload) {
        this.status = status;
        this.payload = (payload != null) ? payload : "";
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static Response ok()                    { return new Response(ResponseStatus.OK, ""); }
    public static Response ok(String payload)      { return new Response(ResponseStatus.OK, payload); }
    public static Response notFound()              { return new Response(ResponseStatus.NOT_FOUND, ""); }
    public static Response error(String message)   { return new Response(ResponseStatus.ERROR, message); }
    public static Response invalidRequest(String m){ return new Response(ResponseStatus.INVALID_REQUEST, m); }

    public static Response forExists(boolean present) {
        return new Response(ResponseStatus.OK, present ? "1" : "0");
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    public ByteBuffer encode() {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payloadBytes.length);
        buf.put(status.code());
        buf.putInt(payloadBytes.length);
        buf.put(payloadBytes);
        buf.flip();
        return buf;
    }

    public static Response decode(ByteBuffer buf) {
        byte code = buf.get();
        ResponseStatus status = ResponseStatus.fromCode(code);
        int payloadLen = buf.getInt();
        String payload = "";
        if (payloadLen > 0) {
            byte[] payloadBytes = new byte[payloadLen];
            buf.get(payloadBytes);
            payload = new String(payloadBytes, StandardCharsets.UTF_8);
        }
        return new Response(status, payload);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ResponseStatus status()   { return status; }
    public String payload()          { return payload; }
    public boolean isOk()            { return status == ResponseStatus.OK; }

    public Optional<String> valuePayload() {
        if (status == ResponseStatus.OK && !payload.isEmpty()) return Optional.of(payload);
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "Response{" + status + (payload.isEmpty() ? "" : " payload='" + payload + "'") + "}";
    }
}
