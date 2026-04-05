package com.kache;

import com.kache.store.KacheStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cascade invalidation — the core differentiator of Kache.
 *
 * These tests prove that when a parent key is deleted, all keys that
 * directly or transitively depend on it are also removed. This is the
 * feature that Redis and Memcached don't have.
 */
class CascadeTest {

    private KacheStore store;

    @BeforeEach
    void setUp() {
        store = new KacheStore();
    }

    @Test
    @DisplayName("Cascade invalidation removes all dependents recursively")
    void cascadeInvalidation_removesAllDependents() {
        // Build a dependency tree:
        //   user:123
        //     ├── user:123:feed
        //     └── user:123:recs
        //           └── user:123:recs:detail
        store.set("user:123", "data", -1, List.of());
        store.set("user:123:feed", "feed", -1, List.of("user:123"));
        store.set("user:123:recs", "recs", -1, List.of("user:123"));
        store.set("user:123:recs:detail", "detail", -1, List.of("user:123:recs"));

        // Delete the root — should cascade to all 4 keys
        int removed = store.delete("user:123");

        assertEquals(4, removed);
        assertNull(store.get("user:123"));
        assertNull(store.get("user:123:feed"));
        assertNull(store.get("user:123:recs"));
        assertNull(store.get("user:123:recs:detail"));
    }

    @Test
    @DisplayName("Cascade invalidation does not affect unrelated keys")
    void cascadeInvalidation_doesNotAffectUnrelated() {
        // Two independent user hierarchies
        store.set("user:123", "data", -1, List.of());
        store.set("user:456", "other", -1, List.of());
        store.set("user:123:feed", "feed", -1, List.of("user:123"));

        // Delete user:123 — user:456 should be untouched
        store.delete("user:123");

        assertNotNull(store.get("user:456")); // untouched
    }

    @Test
    @DisplayName("Cascade stats count each cascaded invalidation")
    void cascadeInvalidation_incrementsCascadeCount() {
        store.set("root", "data", -1, List.of());
        store.set("child1", "data", -1, List.of("root"));
        store.set("child2", "data", -1, List.of("root"));
        store.set("grandchild", "data", -1, List.of("child1"));

        store.delete("root");

        // 3 cascaded invalidations: child1, child2, grandchild
        // (root itself is a direct delete, not counted as cascade)
        var stats = store.getStats();
        assertEquals(3, stats.cascadeInvalidations());
    }

    @Test
    @DisplayName("Deleting a leaf key does not affect its parent")
    void deleteLeaf_doesNotAffectParent() {
        store.set("parent", "data", -1, List.of());
        store.set("child", "data", -1, List.of("parent"));

        // Delete only the child
        int removed = store.delete("child");

        assertEquals(1, removed);
        assertNotNull(store.get("parent")); // parent still exists
    }

    @Test
    @DisplayName("Diamond dependency: key with two parents is removed when either parent is deleted")
    void diamondDependency_removedWhenEitherParentDeleted() {
        // Diamond shape:
        //   A    B
        //    \  /
        //     C
        store.set("A", "data", -1, List.of());
        store.set("B", "data", -1, List.of());
        store.set("C", "data", -1, List.of("A", "B"));

        // Delete A — C should be removed (depends on A)
        store.delete("A");
        assertNull(store.get("C"));

        // B is still there (independent of A)
        assertNotNull(store.get("B"));
    }

    @Test
    @DisplayName("Deep chain: cascade propagates through many levels")
    void deepChain_cascadePropagates() {
        // Build a chain: root → level1 → level2 → ... → level9
        store.set("root", "data", -1, List.of());
        String prev = "root";
        for (int i = 1; i <= 9; i++) {
            String key = "level" + i;
            store.set(key, "data", -1, List.of(prev));
            prev = key;
        }

        // Delete root — all 10 keys should be removed
        int removed = store.delete("root");
        assertEquals(10, removed);

        for (int i = 1; i <= 9; i++) {
            assertNull(store.get("level" + i), "level" + i + " should be null");
        }
    }

    @Test
    @DisplayName("Wide tree: parent with many children")
    void wideTree_manyChildren() {
        store.set("parent", "data", -1, List.of());
        for (int i = 0; i < 100; i++) {
            store.set("child:" + i, "data", -1, List.of("parent"));
        }

        int removed = store.delete("parent");

        assertEquals(101, removed); // parent + 100 children

        // Verify key count is now 0
        assertEquals(0, store.getStats().keyCount());
    }

    @Test
    @DisplayName("Delete key that has already been deleted returns 0")
    void deleteAlreadyDeletedKey_returnsZero() {
        store.set("key", "data", -1, List.of());
        store.delete("key");

        // Second delete should return 0
        assertEquals(0, store.delete("key"));
    }

    @Test
    @DisplayName("Cascade with TTL: expired parent's children are cleaned up on parent GET")
    void cascadeWithTtl_expiredParentCleansUpChildren() throws InterruptedException {
        store.set("parent", "data", 1, List.of()); // 1 second TTL
        store.set("child", "data", -1, List.of("parent"));

        Thread.sleep(1200); // Wait for parent to expire

        // GET parent triggers lazy expire + cascade
        assertNull(store.get("parent"));
        assertNull(store.get("child"));
    }
}
