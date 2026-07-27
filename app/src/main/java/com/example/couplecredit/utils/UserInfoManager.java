package com.example.couplecredit.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.couplecredit.api.AuthApiClient;

/**
 * 用户信息管理工具类
 * 统一管理用户登录信息的获取和情侣关系信息的查询
 */
public class UserInfoManager {
    private static final String TAG = "UserInfoManager";
    private static final String PREFS_NAME = "user_prefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_USER_ID = "id";
    private static final String KEY_USER_ID_INT = "userId";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_RELATIONSHIP_ID = "relationshipId";
    // JWT 鉴权 token
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_REFRESH_TOKEN = "refreshToken";
    private static final long CACHE_TTL_MS = 300_000; // 5分钟

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
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static UserInfoCache cachedUserInfo = null;

    public static boolean saveUserInfo(Context context, String username, int userId) {
        if (context == null || username == null || username.trim().isEmpty() || userId <= 0) {
            Log.e(TAG, "保存用户登录信息失败，参数无效: username=" + username + ", userId=" + userId);
            return false;
        }

        String normalizedUsername = username.trim();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean saved = prefs.edit()
                .putString(KEY_USERNAME, normalizedUsername)
                .putString(KEY_USER_ID, String.valueOf(userId))
                .putInt(KEY_USER_ID_INT, userId)
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .commit();

        if (!saved) {
            Log.e(TAG, "保存用户登录信息失败");
            return false;
        }

        if (cachedUserInfo != null && cachedUserInfo.userId == userId) {
            cachedUserInfo.username = normalizedUsername;
            cachedUserInfo.timestamp = System.currentTimeMillis();
        }
        Log.d(TAG, "用户登录信息已保存: username=" + normalizedUsername + ", userId=" + userId);
        return true;
    }

    /**
     * 获取当前登录用户的完整信息（包括relationship_id）
     */
    public static void getCurrentUserInfo(Context context, UserInfoCallback callback) {
        long startTime = System.currentTimeMillis();
        Log.d(TAG, "开始获取用户信息");
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

            if (cachedUserInfo != null &&
                cachedUserInfo.userId == userId &&
                !cachedUserInfo.isExpired()) {
                long cacheTime = System.currentTimeMillis();
                Log.d(TAG, "使用缓存用户信息，耗时: " + (cacheTime - startTime) + "ms");
                callback.onUserInfoLoaded(userId, username, cachedUserInfo.relationshipId);
                return;
            }

            // 优先从本地缓存读取关系状态（由 API 响应写入）
            int savedRelId = prefs.getInt(KEY_RELATIONSHIP_ID, -1);
            Integer relationshipId = savedRelId > 0 ? savedRelId : null;
            if (relationshipId != null) {
                long cacheTime = System.currentTimeMillis();
                Log.d(TAG, "使用本地缓存关系信息，耗时: " + (cacheTime - startTime) + "ms, relationshipId: " + relationshipId);
                cachedUserInfo = new UserInfoCache(userId, username, relationshipId);
                callback.onUserInfoLoaded(userId, username, relationshipId);
                return;
            }

            AuthApiClient.getRelationshipId(context, userId, new AuthApiClient.SimpleIdCallback() {
                @Override
                public void onSuccess(int relId) {
                    long endTime = System.currentTimeMillis();
                    if (relId > 0) {
                        Log.d(TAG, "获取用户信息完成，耗时: " + (endTime - startTime) + "ms, relationshipId: " + relId);
                        cachedUserInfo = new UserInfoCache(userId, username, relId);
                        callback.onUserInfoLoaded(userId, username, relId);
                    } else {
                        Log.d(TAG, "获取用户信息完成，耗时: " + (endTime - startTime) + "ms, 无情侣关系");
                        cachedUserInfo = new UserInfoCache(userId, username, null);
                        callback.onUserInfoLoaded(userId, username, null);
                    }
                }

                @Override
                public void onError(String e) {
                    long endTime = System.currentTimeMillis();
                    Log.e(TAG, "获取用户信息失败，耗时: " + (endTime - startTime) + "ms, 错误: " + e);
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

        int userId = prefs.getInt(KEY_USER_ID_INT, -1);
        return userId > 0 ? userId : -1;
    }

    /**
     * 获取当前登录用户名
     */
    public static String getCurrentUsername(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, null);
    }

    /**
     * 保存情侣关系ID（由 API 响应调用）
     */
    public static void saveRelationshipId(Context context, Integer relationshipId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (relationshipId != null && relationshipId > 0) {
            prefs.edit().putInt(KEY_RELATIONSHIP_ID, relationshipId).apply();
        } else {
            prefs.edit().remove(KEY_RELATIONSHIP_ID).apply();
        }
        // 更新内存缓存
        if (cachedUserInfo != null) {
            cachedUserInfo.relationshipId = relationshipId;
        }
    }

    /**
     * 保存 JWT token（登录或刷新成功后调用）
     */
    public static void saveTokens(Context context, String accessToken, String refreshToken) {
        if (context == null) return;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (accessToken != null) editor.putString(KEY_ACCESS_TOKEN, accessToken);
        if (refreshToken != null) editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        editor.apply();
    }

    /**
     * 仅更新 access token（refresh 接口返回时调用）
     */
    public static void saveAccessToken(Context context, String accessToken) {
        if (context == null || accessToken == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ACCESS_TOKEN, accessToken).apply();
    }

    /**
     * 获取 access token（不存在返回 null）
     */
    public static String getAccessToken(Context context) {
        if (context == null) return null;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACCESS_TOKEN, null);
    }

    /**
     * 获取 refresh token（不存在返回 null）
     */
    public static String getRefreshToken(Context context) {
        if (context == null) return null;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_REFRESH_TOKEN, null);
    }

    /**
     * 是否持有 token（用于判断是否需要走 refresh 流程）
     */
    public static boolean hasToken(Context context) {
        return getAccessToken(context) != null;
    }

    /**
     * 检查用户是否已登录
     */
    public static boolean isUserLoggedIn(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
                && getCurrentUsername(context) != null
                && getCurrentUserId(context) > 0;
    }

    /**
     * 清除用户登录信息
     */
    public static void clearUserInfo(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        cachedUserInfo = null;

        Log.d(TAG, "用户信息和缓存已清除");
    }
}
