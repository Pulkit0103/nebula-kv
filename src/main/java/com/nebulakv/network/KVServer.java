package com.nebulakv.network;

import com.nebulakv.protocol.RequestHandler;
import com.nebulakv.store.KeyValueStore;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP server that accepts connections and dispatches them to the storage engine.
 *
 * Architecture:
 *   ServerSocketChannel (accept loop, main thread)
 *       ↓ per connection
 *   ClientConnection (dedicated thread from pool)
 *       ↓
 *   RequestHandler → KeyValueStore
 *
 * The server uses a fixed thread pool (configurable). Each accepted connection
 * runs on one thread for the lifetime of that connection. This is the simplest
 * correct model — Phase 25/26 will benchmark alternatives.
 */
public final class KVServer implements Closeable {

    private static final int DEFAULT_PORT = 7777;
    private static final int BACKLOG = 128;

    private final int port;
    private final RequestHandler handler;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocketChannel serverChannel;
    private Thread acceptThread;

    public KVServer(KeyValueStore store) {
        this(store, DEFAULT_PORT, Runtime.getRuntime().availableProcessors() * 2);
    }

    public KVServer(KeyValueStore store, int port, int threads) {
        this.port = port;
        this.handler = new RequestHandler(store);
        this.threadPool = Executors.newFixedThreadPool(threads,
                r -> new Thread(r, "nebula-worker-" + System.nanoTime()));
    }

    public void start() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port), BACKLOG);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "nebula-accept");
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                SocketChannel client = serverChannel.accept();
                if (client == null) continue;
                threadPool.submit(new ClientConnection(client, handler));
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[KVServer] Accept error: " + e.getMessage());
                }
            }
        }
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() throws IOException {
        if (!running.compareAndSet(true, false)) return;
        serverChannel.close();
        threadPool.shutdown();
        try {
            threadPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
