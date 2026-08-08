package com.nebulakv.network;

import com.nebulakv.protocol.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Handles one client connection: read frame → decode request → dispatch → encode response → write frame.
 *
 * Runs on a dedicated thread per connection. This is the simplest correct model;
 * Phase 25 will evaluate whether a NIO Selector or virtual threads improve throughput.
 */
final class ClientConnection implements Runnable {

    private final SocketChannel channel;
    private final RequestHandler handler;

    ClientConnection(SocketChannel channel, RequestHandler handler) {
        this.channel = channel;
        this.handler = handler;
    }

    @Override
    public void run() {
        try (channel) {
            while (true) {
                ByteBuffer requestFrame = FrameCodec.readFrame(channel);
                if (requestFrame == null) break; // clean EOF

                Response response = dispatch(requestFrame);
                FrameCodec.writeFrame(channel, response.encode());
            }
        } catch (IOException e) {
            // Client disconnected abruptly — not an error worth logging at WARN.
        }
    }

    private Response dispatch(ByteBuffer frame) {
        try {
            Request request = Request.decode(frame);
            return handler.handle(request);
        } catch (ProtocolException e) {
            return Response.invalidRequest(e.getMessage());
        } catch (Exception e) {
            return Response.error("Internal error: " + e.getMessage());
        }
    }
}
