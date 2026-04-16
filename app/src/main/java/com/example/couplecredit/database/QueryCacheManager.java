package com.example.couplecredit.database;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据库查询缓存管理器
 * 用于复用查询结果，减少数据库访问
 */
public class QueryCacheManager {
    private static final String TAG = "QueryCacheManager";

    private static final Map<String, CacheEntry> queryCache = new HashMap<>();

    /**
     * 缓存条目
     */
    public static class CacheEntry {
        public final Object data;
        public final long timestamp;

        public CacheEntry(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired(long expiryMs) {
            return System.currentTimeMillis() - timestamp > expiryMs;
        }
    }

    /**
     * 获取缓存
     */
    public static CacheEntry get(String key) {
        synchronized (queryCache) {
            return queryCache.get(key);
        }
    }

    /**
     * 添加缓存
     */
    public static void put(String key, CacheEntry entry) {
        synchronized (queryCache) {
            queryCache.put(key, entry);
        }
    }

    /**
     * 清除特定前缀的缓存
     */
    public static void clearPrefix(String prefix) {
        synchronized (queryCache) {
            queryCache.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
            Log.d(TAG, "已清除前缀 '" + prefix + "' 的缓存");
        }
    }

    /**
     * 清空所有缓存
     */
    public static void clearAll() {
        synchronized (queryCache) {
            queryCache.clear();
            Log.d(TAG, "已清空所有缓存");
        }
    }

    /**
     * 清理过期缓存
     */
    public static void cleanExpired(long expiryMs) {
        synchronized (queryCache) {
            queryCache.entrySet().removeIf(entry -> entry.getValue().isExpired(expiryMs));
        }
    }

    /**
     * 获取缓存大小
     */
    public static int size() {
        synchronized (queryCache) {
            return queryCache.size();
        }
    }
}