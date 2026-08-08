package com.nebulakv.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Response — encode/decode round-trip")
class ResponseCodecTest {

    @Test
    @DisplayName("OK with payload round-trips")
    void okWithPayloadRoundTrip() {
        Response original = Response.ok("hello");
        Response decoded = Response.decode(original.encode());

        assertTrue(decoded.isOk());
        assertEquals("hello", decoded.payload());
    }

    @Test
    @DisplayName("OK with empty payload round-trips")
    void okEmptyRoundTrip() {
        Response original = Response.ok();
        Response decoded = Response.decode(original.encode());

        assertTrue(decoded.isOk());
        assertEquals("", decoded.payload());
    }

    @Test
    @DisplayName("NOT_FOUND round-trips")
    void notFoundRoundTrip() {
        Response original = Response.notFound();
        Response decoded = Response.decode(original.encode());

        assertEquals(ResponseStatus.NOT_FOUND, decoded.status());
        assertFalse(decoded.isOk());
    }

    @Test
    @DisplayName("ERROR with message round-trips")
    void errorRoundTrip() {
        Response original = Response.error("disk full");
        Response decoded = Response.decode(original.encode());

        assertEquals(ResponseStatus.ERROR, decoded.status());
        assertEquals("disk full", decoded.payload());
    }

    @Test
    @DisplayName("EXISTS true encodes as '1'")
    void existsTrueEncodes() {
        Response r = Response.forExists(true);
        assertEquals("1", r.payload());
    }

    @Test
    @DisplayName("EXISTS false encodes as '0'")
    void existsFalseEncodes() {
        Response r = Response.forExists(false);
        assertEquals("0", r.payload());
    }
}
