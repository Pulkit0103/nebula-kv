package com.nebulakv.client;

import com.nebulakv.network.FrameCodec;
import com.nebulakv.protocol.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * Thread-safe NebulaKV client with auto-reconnect.
 *
 * <p>Each operation acquires the internal lock, executes over the TCP channel, and releases it.
 * On any network error the client attempts one reconnect before propagating the exception.
 *
 * <p>Usage:
 * <pre>{@code
 * try (NebulaClient client = NebulaClient.connect("localhost", 7777)) {
 *     client.put("hello", "world");
 *     Optional<String> val = client.get("hello"); // Optional["world"]
 * }
 * }</pre>
 */
public final class NebulaClient implements Closeable {

    private final String host;
    private final int port;
    private volatile SocketChannel channel;
    private final Object lock = new Object();

    private NebulaClient(String host, int port, SocketChannel channel) {
        this.host = host;
        this.port = port;
        this.channel = channel;
    }

    /**
     * Opens a connection to a NebulaKV node.
     *
     * @param host server hostname or IP
     * @param port server KV port (default 7777)
     * @return connected client
     * @throws NebulaClientException if the connection cannot be established
     */
    public static NebulaClient connect(String host, int port) throws NebulaClientException {
        try {
            SocketChannel ch = openChannel(host, port);
            return new NebulaClient(host, port, ch);
        } catch (IOException e) {
            throw new NebulaClientException("Cannot connect to " + host + ":" + port, e);
        }
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Stores a key-value pair. Overwrites any existing value.
     */
    public void put(String key, String value) throws NebulaClientException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Response r = execute(Request.put(key, value));
        if (!r.isOk()) throw new NebulaClientException("PUT failed: " + r.payload());
    }

    /**
     * Retrieves the value for a key.
     *
     * @return the value, or empty if the key does not exist
     */
    public Optional<String> get(String key) throws NebulaClientException {
        Objects.requireNonNull(key, "key");
        Response r = execute(Request.get(key));
        if (r.status() == ResponseStatus.NOT_FOUND) return Optional.empty();
        if (!r.isOk()) throw new NebulaClientException("GET failed: " + r.payload());
        return Optional.of(r.payload());
    }

    /**
     * Deletes a key. Silently succeeds if the key does not exist.
     */
    public void delete(String key) throws NebulaClientException {
        Objects.requireNonNull(key, "key");
        Response r = execute(Request.delete(key));
        if (!r.isOk()) throw new NebulaClientException("DELETE failed: " + r.payload());
    }

    /**
     * Returns true if the key exists.
     */
    public boolean exists(String key) throws NebulaClientException {
        Objects.requireNonNull(key, "key");
        Response r = execute(Request.exists(key));
        if (!r.isOk()) throw new NebulaClientException("EXISTS failed: " + r.payload());
        return "1".equals(r.payload());
    }

    // -------------------------------------------------------------------------
    // Batch convenience methods (sequential single-key calls)
    // -------------------------------------------------------------------------

    /**
     * Puts all entries in the map. Operations are sent one by one in the order
     * returned by {@link Map#entrySet()}; on any failure the remaining keys are
     * skipped and the exception is thrown.
     */
    public void mput(Map<String, String> entries) throws NebulaClientException {
        Objects.requireNonNull(entries, "entries");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /**
     * Gets all listed keys. Keys not found appear as {@link Optional#empty()} in
     * the result; the result map preserves the order of the input list.
     */
    public Map<String, Optional<String>> mget(List<String> keys) throws NebulaClientException {
        Objects.requireNonNull(keys, "keys");
        Map<String, Optional<String>> result = new LinkedHashMap<>(keys.size() * 2);
        for (String key : keys) {
            result.put(key, get(key));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Deletes all listed keys. Missing keys are silently skipped.
     */
    public void mdelete(List<String> keys) throws NebulaClientException {
        Objects.requireNonNull(keys, "keys");
        for (String key : keys) {
            delete(key);
        }
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    /**
     * Closes the underlying TCP channel. The client cannot be reused after this call.
     */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * Returns true if the underlying channel is currently open.
     */
    public boolean isConnected() {
        SocketChannel ch = channel;
        return ch != null && ch.isConnected() && ch.isOpen();
    }

    // -------------------------------------------------------------------------
    // Internal send/reconnect logic
    // -------------------------------------------------------------------------

    private Response execute(Request request) throws NebulaClientException {
        synchronized (lock) {
            try {
                return send(request);
            } catch (IOException first) {
                // Attempt one reconnect, then retry.
                try {
                    reconnect();
                    return send(request);
                } catch (IOException second) {
                    throw new NebulaClientException("Operation failed after reconnect attempt", second);
                }
            }
        }
    }

    private Response send(Request request) throws IOException {
        FrameCodec.writeFrame(channel, request.encode());
        ByteBuffer frame = FrameCodec.readFrame(channel);
        if (frame == null) throw new IOException("Server closed connection");
        return Response.decode(frame);
    }

    private void reconnect() throws IOException {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
        channel = openChannel(host, port);
    }

    private static SocketChannel openChannel(String host, int port) throws IOException {
        SocketChannel ch = SocketChannel.open(new InetSocketAddress(host, port));
        ch.configureBlocking(true);
        return ch;
    }
}
