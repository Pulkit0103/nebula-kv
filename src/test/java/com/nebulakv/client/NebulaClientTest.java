package com.nebulakv.client;

import com.nebulakv.network.KVServer;
import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NebulaClient — integration tests")
class NebulaClientTest {

    private static final int PORT = 17800;

    private KVServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new KVServer(new InMemoryKeyValueStore(), PORT, 8);
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("connect() factory returns usable client")
    void connectFactory() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            assertTrue(c.isConnected());
        }
    }

    @Test
    @DisplayName("put then get returns stored value")
    void putAndGet() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("k", "v");
            assertEquals(Optional.of("v"), c.get("k"));
        }
    }

    @Test
    @DisplayName("get on missing key returns empty")
    void getMissing() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            assertEquals(Optional.empty(), c.get("no-such-key"));
        }
    }

    @Test
    @DisplayName("put overwrites existing value")
    void putOverwrite() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("x", "first");
            c.put("x", "second");
            assertEquals(Optional.of("second"), c.get("x"));
        }
    }

    @Test
    @DisplayName("delete removes the key")
    void delete() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("del-me", "bye");
            c.delete("del-me");
            assertEquals(Optional.empty(), c.get("del-me"));
        }
    }

    @Test
    @DisplayName("delete on missing key does not throw")
    void deleteIdempotent() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            assertDoesNotThrow(() -> c.delete("ghost"));
        }
    }

    @Test
    @DisplayName("exists returns true/false correctly")
    void existsCheck() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            assertFalse(c.exists("missing"));
            c.put("present", "yes");
            assertTrue(c.exists("present"));
        }
    }

    // -------------------------------------------------------------------------
    // Batch operations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mput stores all entries")
    void mput() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            Map<String, String> batch = new LinkedHashMap<>();
            batch.put("a", "1");
            batch.put("b", "2");
            batch.put("c", "3");
            c.mput(batch);
            assertEquals(Optional.of("1"), c.get("a"));
            assertEquals(Optional.of("2"), c.get("b"));
            assertEquals(Optional.of("3"), c.get("c"));
        }
    }

    @Test
    @DisplayName("mget returns values for existing keys and empty for missing")
    void mget() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("p", "alpha");
            c.put("q", "beta");

            Map<String, Optional<String>> results = c.mget(List.of("p", "q", "r"));
            assertEquals(Optional.of("alpha"), results.get("p"));
            assertEquals(Optional.of("beta"),  results.get("q"));
            assertEquals(Optional.empty(),      results.get("r"));
        }
    }

    @Test
    @DisplayName("mdelete removes all listed keys")
    void mdelete() throws IOException {
        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("d1", "v1");
            c.put("d2", "v2");
            c.mdelete(List.of("d1", "d2", "no-exist"));
            assertFalse(c.exists("d1"));
            assertFalse(c.exists("d2"));
        }
    }

    // -------------------------------------------------------------------------
    // Reconnect behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("close then connect is not reused (client is closed)")
    void closedClientIsNotConnected() throws IOException {
        NebulaClient c = NebulaClient.connect("localhost", PORT);
        c.close();
        assertFalse(c.isConnected());
    }

    @Test
    @DisplayName("new client connects successfully after server restart")
    void newClientAfterRestart() throws IOException {
        server.close();
        server = new KVServer(new InMemoryKeyValueStore(), PORT, 8);
        server.start();

        try (NebulaClient c = NebulaClient.connect("localhost", PORT)) {
            c.put("after-restart", "ok");
            assertEquals(Optional.of("ok"), c.get("after-restart"));
        }
    }

    @Test
    @DisplayName("connect to unreachable host throws NebulaClientException")
    void connectFailure() {
        assertThrows(NebulaClientException.class, () -> NebulaClient.connect("localhost", 19999));
    }
}
