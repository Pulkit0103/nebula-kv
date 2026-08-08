package com.nebulakv.network;

import com.nebulakv.protocol.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Optional;

/**
 * Synchronous TCP client for NebulaKV.
 *
 * Used in integration tests and as a reference implementation for the wire protocol.
 * Not thread-safe — one KVClient per thread.
 */
public final class KVClient implements Closeable {

    private final SocketChannel channel;

    public KVClient(String host, int port) throws IOException {
        channel = SocketChannel.open(new InetSocketAddress(host, port));
    }

    public void put(String key, String value) throws IOException {
        Response r = send(Request.put(key, value));
        if (!r.isOk()) throw new IOException("PUT failed: " + r.payload());
    }

    public Optional<String> get(String key) throws IOException {
        Response r = send(Request.get(key));
        if (r.status() == ResponseStatus.NOT_FOUND) return Optional.empty();
        if (!r.isOk()) throw new IOException("GET failed: " + r.payload());
        return Optional.of(r.payload());
    }

    public void delete(String key) throws IOException {
        Response r = send(Request.delete(key));
        if (!r.isOk()) throw new IOException("DELETE failed: " + r.payload());
    }

    public boolean exists(String key) throws IOException {
        Response r = send(Request.exists(key));
        if (!r.isOk()) throw new IOException("EXISTS failed: " + r.payload());
        return "1".equals(r.payload());
    }

    private Response send(Request request) throws IOException {
        FrameCodec.writeFrame(channel, request.encode());
        ByteBuffer responseFrame = FrameCodec.readFrame(channel);
        if (responseFrame == null) throw new IOException("Server closed connection");
        return Response.decode(responseFrame);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
