package com.nebulakv.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Request — encode/decode round-trip")
class RequestCodecTest {

    @Test
    @DisplayName("PUT round-trip")
    void putRoundTrip() {
        Request original = Request.put("user:1", "Alice");
        ByteBuffer buf = original.encode();
        Request decoded = Request.decode(buf);

        assertEquals(CommandType.PUT, decoded.command());
        assertEquals("user:1", decoded.key());
        assertEquals("Alice", decoded.value().orElseThrow());
    }

    @Test
    @DisplayName("GET round-trip")
    void getRoundTrip() {
        Request original = Request.get("user:1");
        Request decoded = Request.decode(original.encode());

        assertEquals(CommandType.GET, decoded.command());
        assertEquals("user:1", decoded.key());
        assertTrue(decoded.value().isEmpty());
    }

    @Test
    @DisplayName("DELETE round-trip")
    void deleteRoundTrip() {
        Request original = Request.delete("user:1");
        Request decoded = Request.decode(original.encode());

        assertEquals(CommandType.DELETE, decoded.command());
        assertEquals("user:1", decoded.key());
    }

    @Test
    @DisplayName("EXISTS round-trip")
    void existsRoundTrip() {
        Request original = Request.exists("user:1");
        Request decoded = Request.decode(original.encode());

        assertEquals(CommandType.EXISTS, decoded.command());
        assertEquals("user:1", decoded.key());
    }

    @Test
    @DisplayName("PUT with Unicode key and value")
    void putUnicodeKeyAndValue() {
        Request original = Request.put("cleé", "valüe");
        Request decoded = Request.decode(original.encode());

        assertEquals("cleé", decoded.key());
        assertEquals("valüe", decoded.value().orElseThrow());
    }

    @Test
    @DisplayName("PUT with large value round-trips correctly")
    void putLargeValue() {
        String largeValue = "x".repeat(10_000);
        Request original = Request.put("big", largeValue);
        Request decoded = Request.decode(original.encode());

        assertEquals(largeValue, decoded.value().orElseThrow());
    }

    @Test
    @DisplayName("Unknown opcode throws ProtocolException")
    void unknownOpcodeThrows() {
        // 1 (opcode) + 4 (key_len) + 3 (key "key") + 4 (value_len) = 12 bytes
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.put((byte) 0xFF); // unknown opcode
        buf.putInt(3);
        buf.put("key".getBytes());
        buf.putInt(0);
        buf.flip();

        assertThrows(IllegalArgumentException.class, () -> Request.decode(buf));
    }

    @Test
    @DisplayName("PUT without value in buffer throws ProtocolException")
    void putMissingValueThrows() {
        // 1 (opcode) + 4 (key_len) + 3 (key "key") + 4 (value_len=0) = 12 bytes
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.put(CommandType.PUT.opcode());
        buf.putInt(3);
        buf.put("key".getBytes());
        buf.putInt(0); // no value — invalid for PUT
        buf.flip();

        assertThrows(ProtocolException.class, () -> Request.decode(buf));
    }
}
