package com.nebulakv.protocol;

import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RESP2 — parser, encoder, command handler")
class RespParserTest {

    private InMemoryKeyValueStore store;
    private RespCommandHandler handler;

    @BeforeEach
    void setUp() {
        store   = new InMemoryKeyValueStore();
        handler = new RespCommandHandler(store);
    }

    // -- parser ---------------------------------------------------------------

    @Test
    @DisplayName("parses bulk string")
    void parseBulkString() {
        byte[] input = "$5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8);
        RespParser.RespValue v = RespParser.parse(input);
        assertInstanceOf(RespParser.RespValue.BulkString.class, v);
        assertEquals("hello", ((RespParser.RespValue.BulkString) v).value());
    }

    @Test
    @DisplayName("parses array of bulk strings (typical redis-cli command)")
    void parseArray() {
        byte[] input = "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        RespParser.RespValue v = RespParser.parse(input);
        assertInstanceOf(RespParser.RespValue.Array.class, v);
        List<RespParser.RespValue> elems = ((RespParser.RespValue.Array) v).elements();
        assertEquals(3, elems.size());
        assertEquals("SET", ((RespParser.RespValue.BulkString) elems.get(0)).value());
    }

    @Test
    @DisplayName("parses null bulk string ($-1)")
    void parseNullBulk() {
        RespParser.RespValue v = RespParser.parse("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(RespParser.RespValue.Null.class, v);
    }

    @Test
    @DisplayName("parses simple string")
    void parseSimpleString() {
        RespParser.RespValue v = RespParser.parse("+OK\r\n".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(RespParser.RespValue.SimpleString.class, v);
        assertEquals("OK", ((RespParser.RespValue.SimpleString) v).value());
    }

    @Test
    @DisplayName("parses integer")
    void parseInteger() {
        RespParser.RespValue v = RespParser.parse(":42\r\n".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(RespParser.RespValue.Integer.class, v);
        assertEquals(42L, ((RespParser.RespValue.Integer) v).value());
    }

    @Test
    @DisplayName("malformed input throws RespException")
    void malformedThrows() {
        assertThrows(RespException.class, () -> RespParser.parse("garbage".getBytes()));
        assertThrows(RespException.class, () -> RespParser.parse(new byte[0]));
    }

    // -- command handler ------------------------------------------------------

    @Test
    @DisplayName("PING returns PONG")
    void ping() {
        String resp = resp("*1\r\n$4\r\nPING\r\n");
        assertEquals("+PONG\r\n", resp);
    }

    @Test
    @DisplayName("SET then GET round-trip")
    void setGet() {
        assertEquals("+OK\r\n", resp("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
        assertEquals("$5\r\nvalue\r\n", resp("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n"));
    }

    @Test
    @DisplayName("GET missing key returns null bulk")
    void getMissing() {
        assertEquals("$-1\r\n", resp("*2\r\n$3\r\nGET\r\n$6\r\nmissing\r\n"));
    }

    @Test
    @DisplayName("DEL returns count of deleted keys")
    void del() {
        store.put("a", "1");
        store.put("b", "2");
        String resp = resp("*3\r\n$3\r\nDEL\r\n$1\r\na\r\n$1\r\nb\r\n");
        assertEquals(":2\r\n", resp);
    }

    @Test
    @DisplayName("EXISTS returns count of existing keys")
    void exists() {
        store.put("x", "1");
        assertEquals(":1\r\n", resp("*2\r\n$6\r\nEXISTS\r\n$1\r\nx\r\n"));
        assertEquals(":0\r\n", resp("*2\r\n$6\r\nEXISTS\r\n$7\r\nmissing\r\n"));
    }

    @Test
    @DisplayName("MSET writes multiple keys, MGET reads them back")
    void msetMget() {
        resp("*5\r\n$4\r\nMSET\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n");
        String mget = resp("*3\r\n$4\r\nMGET\r\n$1\r\na\r\n$1\r\nb\r\n");
        assertTrue(mget.contains("1"));
        assertTrue(mget.contains("2"));
    }

    @Test
    @DisplayName("unknown command returns error")
    void unknownCommand() {
        String resp = resp("*1\r\n$4\r\nNOOP\r\n");
        assertTrue(resp.startsWith("-ERR"));
    }

    private String resp(String input) {
        RespParser.RespValue v = RespParser.parse(input.getBytes(StandardCharsets.UTF_8));
        return new String(handler.handle(v), StandardCharsets.UTF_8);
    }
}
