package com.nebulakv.protocol;

import com.nebulakv.store.KeyValueStore;

import java.util.Optional;

/**
 * Dispatches a decoded Request to the KeyValueStore and builds the Response.
 *
 * This is the boundary between the protocol layer and the storage layer.
 * The TCP layer (Phase 4) will call this after decoding each frame.
 */
public final class RequestHandler {

    private final KeyValueStore store;

    public RequestHandler(KeyValueStore store) {
        this.store = store;
    }

    public Response handle(Request request) {
        return switch (request.command()) {
            case PUT -> {
                store.put(request.key(), request.value().orElseThrow());
                yield Response.ok();
            }
            case GET -> {
                Optional<String> val = store.get(request.key());
                yield val.map(Response::ok).orElse(Response.notFound());
            }
            case DELETE -> {
                store.delete(request.key());
                yield Response.ok();
            }
            case EXISTS -> Response.forExists(store.exists(request.key()));
        };
    }
}
