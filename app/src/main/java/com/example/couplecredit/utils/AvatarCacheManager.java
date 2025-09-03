package com.example.couplecredit.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AvatarUploadApi;

/**
 * 头像缓存管理器
 * 负责管理用户头像的加载、缓存和清理
 */
public class AvatarCacheManager {
    private static final String TAG = "AvatarCacheManager";
    private static final String PREF_NAME = "user_avatars";
    private static final String PREF_AVATAR_URI = "avatar_uri_";
    
    private static AvatarCacheManager instance;
    private Context context;
    
    private AvatarCacheManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * 获取单例实例
     * @param context 上下文
     * @return AvatarCacheManager实例
     */
    public static synchronized AvatarCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new AvatarCacheManager(context);
        }
        return instance;
    }
    
    /**
     * 加载用户头像
     * @param context 上下文
     * @param imageView 要显示头像的ImageView
     * @param userId 用户ID
     * @param avatarUri 头像URI（可能为null）
     */
    public void loadAvatar(Context context, ImageView imageView, int userId, String avatarUri) {
        try {
            // 如果提供了头像URI，优先使用
            if (avatarUri != null && !avatarUri.isEmpty()) {
                loadAvatarFromUri(context, imageView, avatarUri);
                return;
            }
            
            // 尝试从本地缓存加载
            String cachedUri = getCachedAvatarUri(userId);
            if (cachedUri != null && !cachedUri.isEmpty()) {
                loadAvatarFromUri(context, imageView, cachedUri);
                return;
            }
            
            // 尝试从服务器获取
            loadAvatarFromServer(context, imageView, userId);
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading avatar for user " + userId, e);
            // 出错时显示默认头像
            imageView.setImageResource(R.drawable.ic_default_avatar);
        }
    }
    
    /**
     * 从URI加载头像
     */
    private void loadAvatarFromUri(Context context, ImageView imageView, String uri) {
        Glide.with(context)
            .load(uri)
            .transform(new CircleCrop())
            .placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
            .into(imageView);
    }
    
    /**
     * 从服务器加载头像
     */
    private void loadAvatarFromServer(Context context, ImageView imageView, int userId) {
        // 先显示默认头像
        imageView.setImageResource(R.drawable.ic_default_avatar);
        
        // 异步从服务器获取头像
        new Thread(() -> {
            try {
                AvatarUploadApi avatarApi = new AvatarUploadApi();
                avatarApi.getAvatarUrl(userId, new AvatarUploadApi.AvatarUrlCallback() {
                    @Override
                    public void onSuccess(String avatarUrl) {
                        if (avatarUrl != null && !avatarUrl.isEmpty()) {
                            // 缓存头像URI
                            cacheAvatarUri(userId, avatarUrl);
                            // 在主线程更新UI
                            if (context instanceof android.app.Activity) {
                                ((android.app.Activity) context).runOnUiThread(() -> {
                                    loadAvatarFromUri(context, imageView, avatarUrl);
                                });
                            }
                        }
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to get avatar URL for user " + userId + ": " + error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error getting avatar from server for user " + userId, e);
            }
        }).start();
    }
    
    /**
     * 获取缓存的头像URI
     */
    private String getCachedAvatarUri(int userId) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_AVATAR_URI + userId, null);
    }
    
    /**
     * 缓存头像URI
     */
    private void cacheAvatarUri(int userId, String avatarUri) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_AVATAR_URI + userId, avatarUri).apply();
    }
    
    /**
     * 清除指定用户的头像缓存
     * @param userId 用户ID
     */
    public void clearUserAvatarCache(int userId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(PREF_AVATAR_URI + userId).apply();
            Log.d(TAG, "Cleared avatar cache for user " + userId);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing avatar cache for user " + userId, e);
        }
    }
    
    /**
     * 清除所有头像缓存
     */
    public void clearAllAvatarCache() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "Cleared all avatar cache");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing all avatar cache", e);
        }
    }
}