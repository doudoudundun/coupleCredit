package com.example.couplecredit.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 昵称缓存管理器
 * 提供昵称的本地缓存功能，避免重复查询数据库
 */
public class NicknameCache {
    private static final String TAG = "NicknameCache";
    private static final String PREFS_NAME = "nickname_cache";
    private static final String KEY_NICKNAME_PREFIX = "nickname_";
    private static final String KEY_TIMESTAMP_PREFIX = "timestamp_";
    private static final long CACHE_EXPIRE_TIME = 300000; // 5分钟过期
    
    /**
     * 缓存昵称
     * @param context 上下文
     * @param username 用户名
     * @param nickname 昵称
     */
    public static void cacheNickname(Context context, String username, String nickname) {
        if (context == null || username == null) {
            return;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString(KEY_NICKNAME_PREFIX + username, nickname);
        editor.putLong(KEY_TIMESTAMP_PREFIX + username, System.currentTimeMillis());
        editor.apply();
        
        Log.d(TAG, "缓存昵称: " + username + " -> " + nickname);
    }
    
    /**
     * 获取缓存的昵称
     * @param context 上下文
     * @param username 用户名
     * @return 缓存的昵称，如果不存在或已过期则返回null
     */
    public static String getCachedNickname(Context context, String username) {
        if (context == null || username == null) {
            return null;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 检查缓存是否存在
        String cachedNickname = prefs.getString(KEY_NICKNAME_PREFIX + username, null);
        if (cachedNickname == null) {
            return null;
        }
        
        // 检查缓存是否过期
        long timestamp = prefs.getLong(KEY_TIMESTAMP_PREFIX + username, 0);
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - timestamp > CACHE_EXPIRE_TIME) {
            // 缓存已过期，清除缓存
            clearNicknameCache(context, username);
            Log.d(TAG, "昵称缓存已过期: " + username);
            return null;
        }
        
        Log.d(TAG, "使用缓存昵称: " + username + " -> " + cachedNickname);
        return cachedNickname;
    }
    
    /**
     * 清除指定用户的昵称缓存
     * @param context 上下文
     * @param username 用户名
     */
    public static void clearNicknameCache(Context context, String username) {
        if (context == null || username == null) {
            return;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.remove(KEY_NICKNAME_PREFIX + username);
        editor.remove(KEY_TIMESTAMP_PREFIX + username);
        editor.apply();
        
        Log.d(TAG, "清除昵称缓存: " + username);
    }
    
    /**
     * 清除所有昵称缓存
     * @param context 上下文
     */
    public static void clearAllNicknameCache(Context context) {
        if (context == null) {
            return;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        
        Log.d(TAG, "清除所有昵称缓存");
    }
    
    /**
     * 检查昵称缓存是否存在且有效
     * @param context 上下文
     * @param username 用户名
     * @return true如果缓存存在且有效，否则false
     */
    public static boolean isNicknameCacheValid(Context context, String username) {
        if (context == null || username == null) {
            return false;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 检查缓存是否存在
        if (!prefs.contains(KEY_NICKNAME_PREFIX + username)) {
            return false;
        }
        
        // 检查缓存是否过期
        long timestamp = prefs.getLong(KEY_TIMESTAMP_PREFIX + username, 0);
        long currentTime = System.currentTimeMillis();
        
        return currentTime - timestamp <= CACHE_EXPIRE_TIME;
    }
}