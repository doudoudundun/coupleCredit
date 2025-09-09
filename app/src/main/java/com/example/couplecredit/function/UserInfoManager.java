package com.example.couplecredit.function;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.couplecredit.database.CoupleRelationshipHelper;

/**
 * 用户信息管理工具类
 * 统一管理用户登录信息的获取和情侣关系信息的查询
 */
public class UserInfoManager {
    private static final String TAG = "UserInfoManager";
    private static final String PREFS_NAME = "user_prefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_USER_ID = "id";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    
    /**
     * 用户信息回调接口
     */
    public interface UserInfoCallback {
        void onUserInfoLoaded(int userId, String username, Integer relationshipId);
        void onError(String error);
    }
    
    // 缓存用户信息，避免重复网络请求
    private static class UserInfoCache {
        int userId;
        String username;
        Integer relationshipId;
        long timestamp;
        
        UserInfoCache(int userId, String username, Integer relationshipId) {
            this.userId = userId;
            this.username = username;
            this.relationshipId = relationshipId;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 300000; // 300秒过期
        }
    }
    
    private static UserInfoCache cachedUserInfo = null;
    
    /**
     * 获取当前登录用户的完整信息（包括relationship_id）
     */
    public static void getCurrentUserInfo(Context context, UserInfoCallback callback) {
        long startTime = System.currentTimeMillis();
        Log.d(TAG, "开始获取用户信息");
        // SharedPreferences 是一个轻量级的存储类，主要用于存储一些 简单的键值对数据（key-value）
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false);
        
        if (!isLoggedIn) {
            callback.onError("用户未登录");
            return;
        }
        
        String username = prefs.getString(KEY_USERNAME, null);
        String userIdStr = prefs.getString(KEY_USER_ID, null);
        
        if (username == null || userIdStr == null) {
            callback.onError("用户信息不完整");
            return;
        }
        
        try {
            int userId = Integer.parseInt(userIdStr);
            
            // 检查缓存
            if (cachedUserInfo != null && 
                cachedUserInfo.userId == userId && 
                !cachedUserInfo.isExpired()) {
                long cacheTime = System.currentTimeMillis();
                Log.d(TAG, "使用缓存用户信息，耗时: " + (cacheTime - startTime) + "ms");
                callback.onUserInfoLoaded(userId, username, cachedUserInfo.relationshipId);
                return;
            }
            
            // 使用优化的单次查询获取relationship_id
            CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
            coupleHelper.getUserRelationshipIdOptimized(userId, new CoupleRelationshipHelper.RelationshipIdCallback() {
                @Override
                public void onRelationshipIdFound(int relationshipId) {
                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, "获取用户信息完成，耗时: " + (endTime - startTime) + "ms, relationshipId: " + relationshipId);
                    
                    // 更新缓存
                    cachedUserInfo = new UserInfoCache(userId, username, relationshipId);
                    callback.onUserInfoLoaded(userId, username, relationshipId);
                }
                
                @Override
                public void onNoRelationshipFound() {
                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, "获取用户信息完成，耗时: " + (endTime - startTime) + "ms, 无情侣关系");
                    
                    // 更新缓存
                    cachedUserInfo = new UserInfoCache(userId, username, null);
                    callback.onUserInfoLoaded(userId, username, null);
                }
                
                @Override
                public void onError(String error) {
                    long endTime = System.currentTimeMillis();
                    Log.e(TAG, "获取用户信息失败，耗时: " + (endTime - startTime) + "ms, 错误: " + error);
                    callback.onUserInfoLoaded(userId, username, null);
                }
            });
            
        } catch (NumberFormatException e) {
            callback.onError("用户ID格式错误");
        }
    }
    
    /**
     * 获取当前登录用户ID（简单版本）
     */
    public static int getCurrentUserId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userIdStr = prefs.getString(KEY_USER_ID, null);
        
        if (userIdStr != null) {
            try {
                return Integer.parseInt(userIdStr);
            } catch (NumberFormatException e) {
                Log.e(TAG, "用户ID格式错误: " + userIdStr);
            }
        }
        
        return -1; // 返回-1表示获取失败
    }
    
    /**
     * 获取当前登录用户名
     */
    public static String getCurrentUsername(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, null);
    }
    
    /**
     * 检查用户是否已登录
     */
    public static boolean isUserLoggedIn(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }
    
    /**
     * 清除用户登录信息
     */
    public static void clearUserInfo(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        
        // 清空缓存
        cachedUserInfo = null;
        
        Log.d(TAG, "用户信息和缓存已清除");
    }
    
}