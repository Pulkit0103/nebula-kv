package com.nebulakv.protocol;

import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RequestHandler — protocol-to-store dispatch")
class RequestHandlerTest {

    private InMemoryKeyValueStore store;
    private RequestHandler handler;

    @BeforeEach
    void setUp() {
        store = new InMemoryKeyValueStore();
        handler = new RequestHandler(store);
    }

    @Test
    @DisplayName("PUT returns OK and stores the value")
    void putReturnsOkAndStores() {
        Response r = handler.handle(Request.put("k", "v"));
        assertTrue(r.isOk());
        assertEquals("v", store.get("k").orElseThrow());
    }

    @Test
    @DisplayName("GET returns value after PUT")
    void getAfterPutReturnsValue() {
        handler.handle(Request.put("city", "London"));
        Response r = handler.handle(Request.get("city"));

        assertTrue(r.isOk());
        assertEquals("London", r.payload());
    }

    @Test
    @DisplayName("GET on missing key returns NOT_FOUND")
    void getMissingKeyReturnsNotFound() {
        Response r = handler.handle(Request.get("ghost"));
        assertEquals(ResponseStatus.NOT_FOUND, r.status());
    }

    @Test
    @DisplayName("DELETE returns OK and removes the key")
    void deleteReturnsOkAndRemoves() {
        handler.handle(Request.put("tmp", "data"));
        Response r = handler.handle(Request.delete("tmp"));

        assertTrue(r.isOk());
        assertFalse(store.exists("tmp"));
    }

    @Test
    @DisplayName("EXISTS returns '1' when key is present")
    void existsTrueForPresentKey() {
        handler.handle(Request.put("present", "yes"));
        Response r = handler.handle(Request.exists("present"));

        assertTrue(r.isOk());
        assertEquals("1", r.payload());
    }

    @Test
    @DisplayName("EXISTS returns '0' when key is absent")
    void existsFalseForAbsentKey() {
        Response r = handler.handle(Request.exists("absent"));
        assertEquals("0", r.payload());
    }

    @Test
    @DisplayName("full lifecycle: put → get → delete → get returns NOT_FOUND")
    void fullLifecycle() {
        handler.handle(Request.put("item", "pencil"));
        assertEquals("pencil", handler.handle(Request.get("item")).payload());

        handler.handle(Request.delete("item"));
        assertEquals(ResponseStatus.NOT_FOUND, handler.handle(Request.get("item")).status());
    }
}
