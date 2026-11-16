package com.georu.core.internal.utils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Cache management utility class providing memory-based caching with TTL support and statistics collection.
 * This class offers thread-safe caching operations with automatic cleanup of expired entries.
 *
 * <p>Features include:
 * <ul>
 *   <li>Memory-based caching with configurable TTL</li>
 *   <li>Thread-safe operations using ConcurrentHashMap</li>
 *   <li>Automatic cleanup of expired entries</li>
 *   <li>Cache statistics (hit rate, miss count, etc.)</li>
 *   <li>Prefix-based cache removal</li>
 * </ul>
 */
/**
 * @description
 *  1. 캐시 유틸리티
 */
@Component
public class CacheUtils {

    private static final Logger logger = LoggerFactory.getLogger(CacheUtils.class);
    
    // 캐시 저장소
    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    // 캐시 통계
    private static final AtomicLong hitCount = new AtomicLong(0);
    private static final AtomicLong missCount = new AtomicLong(0);
    private static final AtomicLong evictionCount = new AtomicLong(0);
    
    // 기본 TTL (밀리초)
    private static final long DEFAULT_TTL_MS = 300_000; // 5분
    
    // 캐시 정리 스케줄러
    private static ScheduledExecutorService cleanupScheduler;

    /**
     * Initializes the cache system and starts the cleanup scheduler.
     * This method is automatically called after dependency injection is complete.
     * Sets up a daemon thread that runs every minute to clean up expired cache entries.
     *
     */
    @PostConstruct
    public void initialize() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CacheCleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 1분마다 만료된 캐시 정리
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEntries, 1, 1, TimeUnit.MINUTES);
        
        logger.info("CacheUtils initialized with cleanup scheduler");
    }

    /**
     * Cleans up cache resources and shuts down the cleanup scheduler.
     * This method is automatically called before the bean is destroyed.
     * Attempts graceful shutdown of the scheduler with a 5-second timeout.
     *
     */
    @PreDestroy
    public void destroy() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        cache.clear();
        logger.info("CacheUtils destroyed");
    }

    /**
     * Retrieves a value from cache or computes and stores it if not present or expired.
     * Uses the default TTL for newly computed values.
     *
     * @param <T> the type of the cached value
     * @param key the cache key
     * @param supplier the supplier function to compute the value if cache miss occurs
     * @return the cached or newly computed value
     */
    public static <T> T getOrCompute(String key, Supplier<T> supplier) {
        return getOrCompute(key, supplier, DEFAULT_TTL_MS);
    }

    /**
     * Retrieves a value from cache or computes and stores it if not present or expired.
     * Allows specification of custom TTL for newly computed values.
     *
     * @param <T> the type of the cached value
     * @param key the cache key
     * @param supplier the supplier function to compute the value if cache miss occurs
     * @param ttlMs the time-to-live in milliseconds for the cached value
     * @return the cached or newly computed value
     */
    @SuppressWarnings("unchecked")
    public static <T> T getOrCompute(String key, Supplier<T> supplier, long ttlMs) {
        CacheEntry entry = cache.get(key);
        
        // 캐시 히트 및 유효성 확인
        if (entry != null && !entry.isExpired()) {
            hitCount.incrementAndGet();
            logger.debug("Cache hit for key: {}", key);
            return (T) entry.getValue();
        }
        
        // 캐시 미스 - 새로운 값 계산
        missCount.incrementAndGet();
        logger.debug("Cache miss for key: {}, computing new value", key);
        
        T value = supplier.get();
        put(key, value, ttlMs);
        
        return value;
    }

    /**
     * Stores a value in the cache with the default TTL.
     *
     * @param key the cache key
     * @param value the value to cache
     */
    public static void put(String key, Object value) {
        put(key, value, DEFAULT_TTL_MS);
    }

    /**
     * Stores a value in the cache with a specified TTL.
     * If key or value is null, the operation is ignored.
     *
     * @param key the cache key
     * @param value the value to cache
     * @param ttlMs the time-to-live in milliseconds
     */
    public static void put(String key, Object value, long ttlMs) {
        if (key == null || value == null) {
            return;
        }
        
        long expirationTime = System.currentTimeMillis() + ttlMs;
        CacheEntry entry = new CacheEntry(value, expirationTime);
        cache.put(key, entry);
        
        logger.debug("Cached value for key: {} with TTL: {}ms", key, ttlMs);
    }

    /**
     * Retrieves a value from the cache.
     * Returns null if the key is not found or the entry has expired.
     *
     * @param <T> the type of the cached value
     * @param key the cache key
     * @return the cached value or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        CacheEntry entry = cache.get(key);
        
        if (entry != null && !entry.isExpired()) {
            hitCount.incrementAndGet();
            return (T) entry.getValue();
        }
        
        missCount.incrementAndGet();
        return null;
    }

    /**
     * Removes a specific entry from the cache.
     * Increments the eviction count if an entry was actually removed.
     *
     * @param key the cache key to remove
     */
    public static void remove(String key) {
        CacheEntry removed = cache.remove(key);
        if (removed != null) {
            evictionCount.incrementAndGet();
            logger.debug("Removed cache entry for key: {}", key);
        }
    }

    /**
     * Removes all cache entries whose keys start with the specified prefix.
     * Updates the eviction count with the number of removed entries.
     *
     * @param prefix the prefix to match for removal
     */
    public static void removeByPrefix(String prefix) {
        int removedCount = 0;
        
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removedCount++;
            }
        }
        
        evictionCount.addAndGet(removedCount);
        logger.debug("Removed {} cache entries with prefix: {}", removedCount, prefix);
    }

    /**
     * Removes all entries from the cache.
     * Updates the eviction count with the number of cleared entries.
     *
     */
    public static void clear() {
        int size = cache.size();
        cache.clear();
        evictionCount.addAndGet(size);
        logger.info("Cleared all cache entries: {}", size);
    }

    /**
     * Returns the current number of entries in the cache.
     *
     * @return the cache size
     */
    public static int size() {
        return cache.size();
    }

    /**
     * Calculates and returns the cache hit rate.
     * Hit rate is calculated as hits / (hits + misses).
     *
     * @return the hit rate as a value between 0.0 and 1.0
     */
    public static double getHitRate() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Returns comprehensive cache statistics.
     *
     * @return a CacheStatistics object containing hit count, miss count, eviction count, size, and hit rate
     */
    public static CacheStatistics getStatistics() {
        return new CacheStatistics(
            hitCount.get(),
            missCount.get(),
            evictionCount.get(),
            cache.size(),
            getHitRate()
        );
    }

    /**
     * Returns comprehensive cache statistics (alias method).
     * This is an alias for getStatistics() method.
     *
     * @return a CacheStatistics object containing hit count, miss count, eviction count, size, and hit rate
     */
    public static CacheStatistics getCacheStatistics() {
        return getStatistics();
    }

    /**
     * Cleans up expired cache entries.
     * This method is called periodically by the cleanup scheduler.
     * Removes all entries that have exceeded their TTL.
     *
     */
    private void cleanupExpiredEntries() {
        int expiredCount = 0;
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired(currentTime)) {
                cache.remove(entry.getKey());
                expiredCount++;
            }
        }
        
        if (expiredCount > 0) {
            evictionCount.addAndGet(expiredCount);
            logger.debug("Cleaned up {} expired cache entries", expiredCount);
        }
    }

    /**
     * Logs comprehensive cache statistics to the logger.
     * Outputs cache size, hit/miss counts, hit rate, and eviction count.
     *
     */
    public static void logStatistics() {
        CacheStatistics stats = getStatistics();
        logger.info("=== Cache Statistics ===");
        logger.info("Size: {}", stats.getSize());
        logger.info("Hits: {}", stats.getHitCount());
        logger.info("Misses: {}", stats.getMissCount());
        logger.info("Hit Rate: {:.2f}%", stats.getHitRate() * 100);
        logger.info("Evictions: {}", stats.getEvictionCount());
    }

    /**
     * Internal cache entry class that holds cached values with expiration time.
     *
     */
    private static class CacheEntry {
        private final Object value;
        private final long expirationTime;

        /**
         * Constructs a cache entry with value and expiration time.
         *
         * @param value the cached value
         * @param expirationTime the expiration time in milliseconds
         */
        public CacheEntry(Object value, long expirationTime) {
            this.value = value;
            this.expirationTime = expirationTime;
        }

        /**
         * Returns the cached value.
         *
         * @return the cached value
         */
        public Object getValue() {
            return value;
        }

        /**
         * Checks if this cache entry has expired.
         *
         * @return true if expired, false otherwise
         */
        public boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        /**
         * Checks if this cache entry has expired at the given time.
         *
         * @param currentTime the current time in milliseconds
         * @return true if expired, false otherwise
         */
        public boolean isExpired(long currentTime) {
            return currentTime > expirationTime;
        }
    }

    /**
     * Cache statistics data class containing various cache metrics.
     *
     */
    public static class CacheStatistics {
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final int size;
        private final double hitRate;

        /**
         * Constructs cache statistics with all metrics.
         *
         * @param hitCount the number of cache hits
         * @param missCount the number of cache misses
         * @param evictionCount the number of evicted entries
         * @param size the current cache size
         * @param hitRate the hit rate as a value between 0.0 and 1.0
         */
        public CacheStatistics(long hitCount, long missCount, long evictionCount, int size, double hitRate) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.size = size;
            this.hitRate = hitRate;
        }

        /**
         * Returns the number of cache hits.
         *
         * @return the hit count
         */
        public long getHitCount() { return hitCount; }

        /**
         * Returns the number of cache misses.
         *
         * @return the miss count
         */
        public long getMissCount() { return missCount; }

        /**
         * Returns the number of evicted entries.
         *
         * @return the eviction count
         */
        public long getEvictionCount() { return evictionCount; }

        /**
         * Returns the current cache size.
         *
         * @return the cache size
         */
        public int getSize() { return size; }

        /**
         * Returns the cache hit rate.
         *
         * @return the hit rate as a value between 0.0 and 1.0
         */
        public double getHitRate() { return hitRate; }

        @Override
        public String toString() {
            return String.format("CacheStatistics{size=%d, hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d}", 
                               size, hitCount, missCount, hitRate * 100, evictionCount);
        }
    }
}
