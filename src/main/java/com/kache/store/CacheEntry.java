package com.kache.store;

/**
 * Represents a single cached value with an optional expiration time.
 *
 * Design decision: kept intentionally simple — just value + expiry.
 * No access counters, no cost metrics, no value hashing.
 * Every field here is used by at least one command. If it isn't, it doesn't belong.
 */
public class CacheEntry {

    /** The cached string value. */
    public final String value;

    /**
     * Absolute expiration timestamp in epoch milliseconds.
     * A value of -1 means this entry never expires.
     */
    public final long expiresAt;

    /**
     * Creates a new cache entry.
     *
     * @param value     the string value to cache
     * @param expiresAt epoch ms when this entry expires, or -1 for no expiry
     */
    public CacheEntry(String value, long expiresAt) {
        this.value = value;
        this.expiresAt = expiresAt;
    }

    /**
     * Checks if this entry has passed its expiration time.
     * Entries with expiresAt == -1 never expire.
     *
     * @return true if the entry is expired and should be invalidated
     */
    public boolean isExpired() {
        return expiresAt != -1 && System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        return "CacheEntry{value='" + value + "', expiresAt=" + expiresAt + "}";
    }
}
