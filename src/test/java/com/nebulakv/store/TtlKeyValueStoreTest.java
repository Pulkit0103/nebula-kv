package com.nebulakv.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TtlKeyValueStore — per-key expiry")
class TtlKeyValueStoreTest {

    private TtlKeyValueStore store;

    @BeforeEach
    void setUp() {
        store = new TtlKeyValueStore(new InMemoryKeyValueStore());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("put without TTL is persistent — ttl() returns -1")
    void persistentKey() {
        store.put("k", "v");
        assertEquals(Optional.of("v"), store.get("k"));
        assertEquals(-1L, store.ttl("k"));
    }

    @Test
    @DisplayName("key is absent before put — ttl() returns -2")
    void missingKey() {
        assertEquals(-2L, store.ttl("no-such-key"));
    }

    @Test
    @DisplayName("expired key is invisible to get immediately after deadline")
    void lazyExpiryOnGet() throws InterruptedException {
        store.put("x", "hello", 50);
        assertEquals(Optional.of("hello"), store.get("x"));
        Thread.sleep(60);
        assertEquals(Optional.empty(), store.get("x"));
    }

    @Test
    @DisplayName("expired key is invisible to exists immediately after deadline")
    void lazyExpiryOnExists() throws InterruptedException {
        store.put("x", "hello", 50);
        assertTrue(store.exists("x"));
        Thread.sleep(60);
        assertFalse(store.exists("x"));
    }

    @Test
    @DisplayName("ttl() returns positive remaining millis before expiry")
    void ttlPositiveBeforeExpiry() {
        store.put("y", "val", 5_000);
        long remaining = store.ttl("y");
        assertTrue(remaining > 0 && remaining <= 5_000, "remaining=" + remaining);
    }

    @Test
    @DisplayName("ttl() returns -2 after key has expired")
    void ttlNegativeAfterExpiry() throws InterruptedException {
        store.put("z", "val", 50);
        Thread.sleep(60);
        assertEquals(-2L, store.ttl("z"));
    }

    @Test
    @DisplayName("background sweeper evicts expired keys")
    void backgroundSweeper() throws InterruptedException {
        store.put("sweep-me", "v", 100);
        // Wait longer than TTL + one sweep cycle
        Thread.sleep(TtlKeyValueStore.SWEEP_INTERVAL_MS + 200);
        assertTrue(store.expiredCount() >= 1, "sweeper should have evicted at least 1 key");
    }

    @Test
    @DisplayName("overwriting a TTL key with plain put removes expiry")
    void overwriteRemovesExpiry() throws InterruptedException {
        store.put("k", "old", 100);
        store.put("k", "new");         // no TTL
        Thread.sleep(150);
        assertEquals(Optional.of("new"), store.get("k"), "key must survive past original TTL");
        assertEquals(-1L, store.ttl("k"));
    }

    @Test
    @DisplayName("delete removes expiry metadata")
    void deleteRemovesExpiry() {
        store.put("d", "v", 5_000);
        store.delete("d");
        assertEquals(-2L, store.ttl("d"));
        assertFalse(store.exists("d"));
    }

    @Test
    @DisplayName("many keys with different TTLs expire independently")
    void multipleIndependentTtls() throws InterruptedException {
        store.put("fast", "f", 80);
        store.put("slow", "s", 5_000);

        Thread.sleep(100);
        assertFalse(store.exists("fast"), "fast key should have expired");
        assertTrue(store.exists("slow"),  "slow key must still be live");
    }

    @Test
    @DisplayName("invalid TTL (zero or negative) throws")
    void invalidTtlThrows() {
        assertThrows(IllegalArgumentException.class, () -> store.put("k", "v", 0));
        assertThrows(IllegalArgumentException.class, () -> store.put("k", "v", -1));
    }
}
