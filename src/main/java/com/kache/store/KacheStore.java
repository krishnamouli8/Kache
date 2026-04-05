package com.kache.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core cache engine with dependency-aware cascade invalidation.
 *
 * Architecture:
 * - {@code data} holds all cached key-value entries.
 * - {@code deps} maps a parent key → set of child keys that depend on it.
 * - When a parent key is deleted, all children (and their children, recursively) are also deleted.
 *
 * TTL expiry model:
 * - A background cleaner thread runs every 10 seconds to proactively expire TTL'd entries
 *   and cascade-invalidate their dependents.
 * - Additionally, GET performs lazy expiry if it encounters a stale entry between cleaner runs.
 * - This dual approach ensures children of expired parents are cleaned up even if the parent
 *   is never accessed again (unlike a purely lazy model).
 *
 * Concurrency model:
 * - A single ReentrantReadWriteLock guards both maps.
 * - Reads (GET) acquire the read lock — multiple concurrent reads are allowed.
 * - Writes (SET, DEL, clean) acquire the write lock — exclusive access.
 * - Lock upgrade (read → write) happens lazily on expired entry detection during GET.
 *
 * This is intentionally NOT striped or segmented. A single lock is fine at the scale
 * this project targets, and it keeps the cascade invalidation logic simple and correct.
 */
public class KacheStore {

    private static final Logger logger = LoggerFactory.getLogger(KacheStore.class);

    /** Primary data store: key → CacheEntry (value + expiry) */
    private final ConcurrentHashMap<String, CacheEntry> data = new ConcurrentHashMap<>();

    /** Dependency graph: parent key → set of child keys that depend on it */
    private final ConcurrentHashMap<String, Set<String>> deps = new ConcurrentHashMap<>();

    /** Single read-write lock protecting both data and deps for consistent cascade operations */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Background cleaner that proactively expires TTL'd entries every 10 seconds.
     * Uses a daemon thread so it won't prevent JVM shutdown.
     */
    private final ScheduledExecutorService cleaner;

    // --- Stats counters (atomic for lock-free reads) ---
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong cascadeCount = new AtomicLong();

    public KacheStore() {
        // Start background cleaner on a daemon thread — won't block JVM shutdown
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kache-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanExpired, 10, 10, TimeUnit.SECONDS);
        logger.debug("Background TTL cleaner started (interval=10s)");
    }

    /**
     * Scans all entries and removes any that have expired, cascading to their dependents.
     * Called every 10 seconds by the background cleaner thread.
     * This ensures children of expired parents are cleaned up proactively,
     * not just when the parent happens to be accessed.
     */
    private void cleanExpired() {
        lock.writeLock().lock();
        try {
            List<String> expired = data.entrySet().stream()
                    .filter(e -> e.getValue().isExpired())
                    .map(Map.Entry::getKey)
                    .toList();
            expired.forEach(this::invalidate);
            if (!expired.isEmpty()) {
                logger.debug("Background cleaner removed {} expired entries", expired.size());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Shuts down the background cleaner thread.
     * Call this when the KacheStore is no longer needed (e.g., during server shutdown).
     */
    public void shutdown() {
        cleaner.shutdownNow();
        logger.debug("Background TTL cleaner stopped");
    }

    /**
     * Stores a key-value pair with optional TTL and dependency declarations.
     *
     * @param key        the cache key
     * @param value      the string value to cache
     * @param ttlSeconds time-to-live in seconds; -1 or 0 means no expiry
     * @param dependsOn  list of parent keys this entry depends on (can be empty)
     * @throws IllegalArgumentException if key or value is null
     */
    public void set(String key, String value, long ttlSeconds, List<String> dependsOn) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key must not be null or blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }

        lock.writeLock().lock();
        try {
            // Calculate absolute expiry time from relative TTL
            long expiresAt = ttlSeconds > 0
                    ? System.currentTimeMillis() + ttlSeconds * 1000
                    : -1;

            data.put(key, new CacheEntry(value, expiresAt));

            // Register this key as a dependent of each parent
            for (String parent : dependsOn) {
                deps.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(key);
            }

            logger.debug("SET {} (ttl={}s, deps={})", key, ttlSeconds, dependsOn);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Retrieves a cached value by key.
     *
     * If the entry exists but is expired, it triggers a lazy invalidation:
     * the read lock is released, a write lock is acquired, and the expired entry
     * (plus any cascade dependents) is removed. This supplements the background
     * cleaner for entries accessed between cleanup intervals.
     *
     * @param key the cache key to look up
     * @return the cached value, or null if not found / expired
     */
    public String get(String key) {
        if (key == null || key.isBlank()) {
            logger.warn("GET called with null/blank key");
            return null;
        }

        lock.readLock().lock();
        try {
            CacheEntry entry = data.get(key);

            // Key doesn't exist at all
            if (entry == null) {
                misses.incrementAndGet();
                logger.debug("GET {} → MISS (not found)", key);
                return null;
            }

            // Key exists but has expired — need to upgrade to write lock to invalidate
            if (entry.isExpired()) {
                // Release read lock before acquiring write lock (lock upgrade not supported)
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    // Re-check under write lock — another thread may have already cleaned it up
                    CacheEntry recheck = data.get(key);
                    if (recheck != null && recheck.isExpired()) {
                        logger.debug("GET {} → expired, triggering lazy invalidation", key);
                        invalidate(key);
                    }
                    misses.incrementAndGet();
                    return null;
                } finally {
                    // Downgrade: acquire read lock before releasing write lock,
                    // then the outer finally releases the read lock cleanly
                    lock.readLock().lock();
                    lock.writeLock().unlock();
                }
            }

            // Cache hit
            hits.incrementAndGet();
            logger.debug("GET {} → HIT", key);
            return entry.value;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deletes a key and all its transitive dependents (cascade invalidation).
     *
     * @param key the key to delete
     * @return total number of keys removed (including cascaded children)
     */
    public int delete(String key) {
        if (key == null || key.isBlank()) {
            logger.warn("DEL called with null/blank key");
            return 0;
        }

        lock.writeLock().lock();
        try {
            int count = invalidate(key);
            if (count > 0) {
                logger.debug("DEL {} → removed {} key(s) (including cascades)", key, count);
            }
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Recursively removes a key and all its dependents from both data and deps maps.
     *
     * This is the core cascade invalidation algorithm:
     * 1. Remove the key from the data map
     * 2. Look up its children in the deps map
     * 3. Recursively invalidate each child
     *
     * IMPORTANT: Caller must hold the write lock.
     *
     * @param key the key to invalidate
     * @return total number of keys removed
     */
    private int invalidate(String key) {
        if (!data.containsKey(key)) return 0;

        data.remove(key);
        int count = 1;

        // Remove this key's dependency edges and cascade to children
        Set<String> children = deps.remove(key);
        if (children != null) {
            for (String child : children) {
                cascadeCount.incrementAndGet();
                count += invalidate(child);
            }
        }
        return count;
    }

    /**
     * Returns the set of keys that directly depend on the given key.
     * Returns an empty set if the key has no dependents.
     *
     * @param key the parent key
     * @return unmodifiable set of dependent key names
     */
    public Set<String> getDependents(String key) {
        return deps.getOrDefault(key, Collections.emptySet());
    }

    /**
     * Returns a snapshot of current store statistics.
     *
     * @return immutable stats record
     */
    public StoreStats getStats() {
        return new StoreStats(
                data.size(),
                hits.get(),
                misses.get(),
                cascadeCount.get(),
                deps.values().stream().mapToInt(Set::size).sum()
        );
    }

    /**
     * Immutable snapshot of store metrics.
     * Used by the HTTP API (/stats) and STATS TCP command.
     */
    public record StoreStats(
            int keyCount,
            long hits,
            long misses,
            long cascadeInvalidations,
            int depEdgeCount
    ) {
        /**
         * Calculates the cache hit rate as a ratio of hits to total requests.
         *
         * @return hit rate between 0.0 and 1.0, or 0.0 if no requests yet
         */
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
