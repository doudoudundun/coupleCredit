package com.example.couplecredit.function;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.couplecredit.CoupleRelationshipHelper;

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
    
    /**
     * 获取当前登录用户的完整信息（包括relationship_id）
     */
    public static void getCurrentUserInfo(Context context, UserInfoCallback callback) {
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
            
            // 查询用户的情侣关系信息
            CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
            coupleHelper.getCoupleInfo(userId, new CoupleRelationshipHelper.CoupleInfoCallback() {
                @Override
                public void onCoupleFound(int coupleId, String coupleName, String coupleNickname) {
                    // 有情侣关系，需要获取relationship_id
                    coupleHelper.getUserRelationshipId(userId, new CoupleRelationshipHelper.RelationshipIdCallback() {
                        @Override
                        public void onRelationshipIdFound(int relationshipId) {
                            callback.onUserInfoLoaded(userId, username, relationshipId);
                        }
                        
                        @Override
                        public void onNoRelationshipFound() {
                            callback.onUserInfoLoaded(userId, username, null);
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "获取relationship_id失败: " + error);
                            callback.onUserInfoLoaded(userId, username, null);
                        }
                    });
                }
                
                @Override
                public void onNoCoupleFound() {
                    // 没有情侣关系，relationship_id为null
                    callback.onUserInfoLoaded(userId, username, null);
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "查询情侣信息失败: " + error);
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
        Log.d(TAG, "用户信息已清除");
    }
    
}