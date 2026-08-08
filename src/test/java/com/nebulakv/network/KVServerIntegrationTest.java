package com.nebulakv.network;

import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KVServer — TCP integration tests")
class KVServerIntegrationTest {

    private static final int PORT = 17777; // avoid 7777 in case it's in use on CI

    private KVServer server;
    private InMemoryKeyValueStore store;

    @BeforeEach
    void startServer() throws IOException {
        store = new InMemoryKeyValueStore();
        server = new KVServer(store, PORT, 8);
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    // -------------------------------------------------------------------------
    // Basic CRUD over TCP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT then GET returns stored value")
    void putThenGet() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            client.put("greeting", "hello");
            Optional<String> result = client.get("greeting");
            assertEquals(Optional.of("hello"), result);
        }
    }

    @Test
    @DisplayName("GET on missing key returns empty")
    void getMissingKey() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            assertEquals(Optional.empty(), client.get("nobody"));
        }
    }

    @Test
    @DisplayName("DELETE removes the key")
    void deleteRemovesKey() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            client.put("temp", "data");
            client.delete("temp");
            assertEquals(Optional.empty(), client.get("temp"));
        }
    }

    @Test
    @DisplayName("EXISTS returns true and false correctly")
    void existsCheck() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            assertFalse(client.exists("new-key"));
            client.put("new-key", "new-value");
            assertTrue(client.exists("new-key"));
        }
    }

    @Test
    @DisplayName("Multiple sequential operations on one connection")
    void multipleOperationsOnOneConnection() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            for (int i = 0; i < 100; i++) {
                client.put("key:" + i, "val:" + i);
            }
            for (int i = 0; i < 100; i++) {
                assertEquals(Optional.of("val:" + i), client.get("key:" + i));
            }
            assertEquals(100, store.size());
        }
    }

    @Test
    @DisplayName("Overwrite via PUT is reflected on GET")
    void overwriteIsReflected() throws IOException {
        try (KVClient client = new KVClient("localhost", PORT)) {
            client.put("env", "dev");
            client.put("env", "prod");
            assertEquals(Optional.of("prod"), client.get("env"));
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent clients
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Multiple concurrent clients do not corrupt each other's data")
    void concurrentClientsDoNotCorruptData() throws Exception {
        int numClients = 10;
        int opsPerClient = 50;
        ExecutorService pool = Executors.newFixedThreadPool(numClients);
        List<Future<Void>> futures = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);

        for (int c = 0; c < numClients; c++) {
            final int clientId = c;
            futures.add(pool.submit(() -> {
                try (KVClient client = new KVClient("localhost", PORT)) {
                    for (int i = 0; i < opsPerClient; i++) {
                        String key = "client:" + clientId + ":key:" + i;
                        String expected = "v" + clientId + "-" + i;
                        client.put(key, expected);
                        Optional<String> actual = client.get(key);
                        if (!actual.equals(Optional.of(expected))) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }

        for (Future<Void> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get(), "Concurrent clients produced " + errors.get() + " data errors");
    }

    @Test
    @DisplayName("Server survives client disconnect mid-connection")
    void serverSurvivesAbruptDisconnect() throws IOException {
        // Open and immediately close without sending anything.
        try (KVClient client = new KVClient("localhost", PORT)) {
            // immediate close
        }
        // Server must still be running and accept new connections.
        assertTrue(server.isRunning());
        try (KVClient client = new KVClient("localhost", PORT)) {
            client.put("after-disconnect", "ok");
            assertEquals(Optional.of("ok"), client.get("after-disconnect"));
        }
    }
}
