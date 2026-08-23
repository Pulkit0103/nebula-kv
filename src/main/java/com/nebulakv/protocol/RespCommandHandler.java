package com.nebulakv.protocol;

import com.nebulakv.store.KeyValueStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dispatches parsed RESP2 commands to a KeyValueStore.
 *
 * Supported commands (subset compatible with redis-cli):
 *   GET key
 *   SET key value [EX seconds | PX milliseconds]
 *   DEL key [key ...]
 *   EXISTS key [key ...]
 *   MGET key [key ...]
 *   MSET key value [key value ...]
 *   PING [message]
 *   COMMAND (minimal stub — redis-cli sends this on connect)
 */
public final class RespCommandHandler {

    private final KeyValueStore store;

    public RespCommandHandler(KeyValueStore store) {
        this.store = store;
    }

    /**
     * Handles one RESP array command. Returns the encoded RESP2 response bytes.
     */
    public byte[] handle(RespParser.RespValue value) {
        if (!(value instanceof RespParser.RespValue.Array arr)) {
            return RespEncoder.error("expected array command");
        }
        List<RespParser.RespValue> elems = arr.elements();
        if (elems.isEmpty()) {
            return RespEncoder.error("empty command");
        }
        String cmd = asString(elems.get(0)).toUpperCase();
        return switch (cmd) {
            case "PING"    -> handlePing(elems);
            case "GET"     -> handleGet(elems);
            case "SET"     -> handleSet(elems);
            case "DEL"     -> handleDel(elems);
            case "EXISTS"  -> handleExists(elems);
            case "MGET"    -> handleMget(elems);
            case "MSET"    -> handleMset(elems);
            case "COMMAND" -> RespEncoder.ok();  // stub for redis-cli handshake
            default        -> RespEncoder.error("unknown command '" + cmd + "'");
        };
    }

    // -- command handlers ----------------------------------------------------

    private byte[] handlePing(List<RespParser.RespValue> elems) {
        if (elems.size() == 1) return "+PONG\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return RespEncoder.bulkString(asString(elems.get(1)));
    }

    private byte[] handleGet(List<RespParser.RespValue> elems) {
        if (elems.size() < 2) return RespEncoder.error("GET requires key");
        Optional<String> val = store.get(asString(elems.get(1)));
        return val.map(RespEncoder::bulkString).orElseGet(RespEncoder::nullBulk);
    }

    private byte[] handleSet(List<RespParser.RespValue> elems) {
        if (elems.size() < 3) return RespEncoder.error("SET requires key value");
        store.put(asString(elems.get(1)), asString(elems.get(2)));
        // EX/PX options ignored in this phase — TTL support is in TtlKeyValueStore
        return RespEncoder.ok();
    }

    private byte[] handleDel(List<RespParser.RespValue> elems) {
        if (elems.size() < 2) return RespEncoder.error("DEL requires key");
        long deleted = 0;
        for (int i = 1; i < elems.size(); i++) {
            String key = asString(elems.get(i));
            if (store.exists(key)) {
                store.delete(key);
                deleted++;
            }
        }
        return RespEncoder.integer(deleted);
    }

    private byte[] handleExists(List<RespParser.RespValue> elems) {
        if (elems.size() < 2) return RespEncoder.error("EXISTS requires key");
        long count = 0;
        for (int i = 1; i < elems.size(); i++) {
            if (store.exists(asString(elems.get(i)))) count++;
        }
        return RespEncoder.integer(count);
    }

    private byte[] handleMget(List<RespParser.RespValue> elems) {
        if (elems.size() < 2) return RespEncoder.error("MGET requires key");
        List<String> values = new ArrayList<>();
        for (int i = 1; i < elems.size(); i++) {
            values.add(store.get(asString(elems.get(i))).orElse(null));
        }
        return RespEncoder.array(values);
    }

    private byte[] handleMset(List<RespParser.RespValue> elems) {
        if (elems.size() < 3 || (elems.size() % 2) == 0) {
            return RespEncoder.error("MSET requires key-value pairs");
        }
        for (int i = 1; i < elems.size(); i += 2) {
            store.put(asString(elems.get(i)), asString(elems.get(i + 1)));
        }
        return RespEncoder.ok();
    }

    // -- helpers -------------------------------------------------------------

    private static String asString(RespParser.RespValue v) {
        if (v instanceof RespParser.RespValue.BulkString b)   return b.value();
        if (v instanceof RespParser.RespValue.SimpleString s) return s.value();
        throw new RespException("expected string, got " + v.getClass().getSimpleName());
    }
}
