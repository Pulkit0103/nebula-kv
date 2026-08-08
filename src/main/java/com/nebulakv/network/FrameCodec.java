package com.nebulakv.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Length-prefixed framing over a raw TCP byte stream.
 *
 * Frame format:
 *   [4 bytes: total frame length (big-endian, NOT including these 4 bytes)]
 *   [N bytes: payload (Request or Response encoded bytes)]
 *
 * Why length-prefix? TCP is a stream protocol — a read may return part of a
 * frame or multiple frames. The length prefix lets us reconstruct exact frames
 * without relying on delimiters, which break when payloads contain the
 * delimiter character.
 */
public final class FrameCodec {

    private static final int LENGTH_HEADER_SIZE = 4;
    private static final int MAX_FRAME_SIZE = 64 * 1024 * 1024; // 64 MB guard

    private FrameCodec() {}

    /**
     * Writes a framed payload to a blocking SocketChannel.
     * Blocks until the entire frame is written or an IOException occurs.
     */
    public static void writeFrame(SocketChannel channel, ByteBuffer payload) throws IOException {
        int payloadSize = payload.remaining();
        ByteBuffer frame = ByteBuffer.allocate(LENGTH_HEADER_SIZE + payloadSize);
        frame.putInt(payloadSize);
        frame.put(payload);
        frame.flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /**
     * Reads one complete frame from a blocking SocketChannel.
     * Returns the payload ByteBuffer (flipped, ready to read), or null if the
     * channel reached EOF (client disconnected cleanly).
     */
    public static ByteBuffer readFrame(SocketChannel channel) throws IOException {
        ByteBuffer lengthBuf = ByteBuffer.allocate(LENGTH_HEADER_SIZE);
        if (!readFully(channel, lengthBuf)) return null;

        int payloadSize = lengthBuf.getInt();
        if (payloadSize <= 0 || payloadSize > MAX_FRAME_SIZE) {
            throw new IOException("Invalid frame size: " + payloadSize);
        }

        ByteBuffer payload = ByteBuffer.allocate(payloadSize);
        if (!readFully(channel, payload)) return null;
        // readFully already flips the buffer — do not flip again.
        return payload;
    }

    /**
     * Fills a ByteBuffer completely from the channel.
     * Returns false only on clean EOF before any bytes of this buffer were read.
     */
    private static boolean readFully(SocketChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n == -1) {
                if (buf.position() == 0) return false;
                throw new IOException("Unexpected EOF in middle of frame");
            }
        }
        buf.flip();
        return true;
    }
}
