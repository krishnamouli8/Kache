package com.kache;

import com.kache.store.KacheStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KacheStore} — basic operations.
 * Tests SET/GET roundtrip, TTL expiry, DEL, and miss handling.
 */
class KacheStoreTest {

    private KacheStore store;

    @BeforeEach
    void setUp() {
        store = new KacheStore();
    }

    @Test
    @DisplayName("SET and GET roundtrip returns the stored value")
    void setAndGet_roundtrip() {
        store.set("name", "kache", -1, List.of());

        assertEquals("kache", store.get("name"));
    }

    @Test
    @DisplayName("GET on a missing key returns null and increments misses")
    void get_missingKey_returnsNull() {
        assertNull(store.get("nonexistent"));

        var stats = store.getStats();
        assertEquals(1, stats.misses());
        assertEquals(0, stats.hits());
    }

    @Test
    @DisplayName("SET overwrites existing value for the same key")
    void set_overwritesExistingValue() {
        store.set("key", "first", -1, List.of());
        store.set("key", "second", -1, List.of());

        assertEquals("second", store.get("key"));
    }

    @Test
    @DisplayName("DEL removes the key and returns count of 1")
    void delete_removesKey() {
        store.set("key", "value", -1, List.of());

        int removed = store.delete("key");

        assertEquals(1, removed);
        assertNull(store.get("key"));
    }

    @Test
    @DisplayName("DEL on a missing key returns 0")
    void delete_missingKey_returnsZero() {
        assertEquals(0, store.delete("nonexistent"));
    }

    @Test
    @DisplayName("TTL expiry makes the entry inaccessible after the timeout")
    void ttlExpiry_returnsNullAfterTimeout() throws InterruptedException {
        // Set with a 1-second TTL
        store.set("temp", "data", 1, List.of());

        // Should be accessible immediately
        assertEquals("data", store.get("temp"));

        // Wait for expiry (1 second + buffer)
        Thread.sleep(1200);

        // Should now return null (lazy expiry triggered by GET)
        assertNull(store.get("temp"));
    }

    @Test
    @DisplayName("Entry without TTL (ttl=-1) never expires")
    void noTtl_neverExpires() throws InterruptedException {
        store.set("permanent", "data", -1, List.of());

        Thread.sleep(100); // Brief wait to verify no accidental expiry

        assertEquals("data", store.get("permanent"));
    }

    @Test
    @DisplayName("Stats track hits and misses accurately")
    void stats_trackHitsAndMisses() {
        store.set("exists", "value", -1, List.of());

        store.get("exists");    // hit
        store.get("exists");    // hit
        store.get("missing");   // miss

        var stats = store.getStats();
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.keyCount());
        assertEquals(2.0 / 3.0, stats.hitRate(), 0.01);
    }

    @Test
    @DisplayName("SET with null key throws IllegalArgumentException")
    void set_nullKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> store.set(null, "value", -1, List.of()));
    }

    @Test
    @DisplayName("SET with null value throws IllegalArgumentException")
    void set_nullValue_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> store.set("key", null, -1, List.of()));
    }

    @Test
    @DisplayName("GET with null key returns null without throwing")
    void get_nullKey_returnsNull() {
        assertNull(store.get(null));
    }

    @Test
    @DisplayName("DEL with null key returns 0 without throwing")
    void delete_nullKey_returnsZero() {
        assertEquals(0, store.delete(null));
    }

    @Test
    @DisplayName("getDependents returns empty set for key with no dependents")
    void getDependents_noDeps_returnsEmpty() {
        store.set("alone", "value", -1, List.of());

        assertTrue(store.getDependents("alone").isEmpty());
    }

    @Test
    @DisplayName("getDependents returns the dependent keys")
    void getDependents_returnsDeps() {
        store.set("parent", "value", -1, List.of());
        store.set("child1", "value", -1, List.of("parent"));
        store.set("child2", "value", -1, List.of("parent"));

        var dependents = store.getDependents("parent");
        assertEquals(2, dependents.size());
        assertTrue(dependents.contains("child1"));
        assertTrue(dependents.contains("child2"));
    }
}
