package com.example.couplecredit.api;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 头像上传API
 * 负责处理头像的上传和获取
 */
public class AvatarUploadApi {
    private static final String TAG = "AvatarUploadApi";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    
    /**
     * 头像上传回调接口
     */
    public interface AvatarUploadCallback {
        void onUploadSuccess(String avatarUrl);
        void onUploadError(String error);
    }
    
    /**
     * 头像上传回调接口（兼容旧版本）
     */
    public interface UploadCallback {
        void onSuccess(String avatarUrl);
        void onError(String error);
    }
    
    /**
     * 头像URL获取回调接口
     */
    public interface AvatarUrlCallback {
        void onSuccess(String avatarUrl);
        void onError(String error);
    }
    
    /**
     * 上传头像到服务器（静态方法）
     * @param context 上下文
     * @param userId 用户ID
     * @param imageUri 图片URI
     * @param callback 上传回调
     */
    public static void uploadAvatar(Context context, int userId, Uri imageUri, AvatarUploadCallback callback) {
        executor.execute(() -> {
            try {
                // 模拟上传过程
                Thread.sleep(1000); // 模拟网络延迟
                
                // 这里应该是实际的网络上传逻辑
                // 目前返回一个模拟的URL
                String avatarUrl = "https://example.com/avatars/user_" + userId + "_" + System.currentTimeMillis() + ".jpg";
                
                if (callback != null) {
                    callback.onUploadSuccess(avatarUrl);
                }
                
                Log.d(TAG, "Avatar uploaded successfully for user " + userId + ": " + avatarUrl);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to upload avatar for user " + userId, e);
                if (callback != null) {
                    callback.onUploadError("上传失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 上传头像到服务器（兼容旧版本回调）
     * @param context 上下文
     * @param userId 用户ID
     * @param imageUri 图片URI
     * @param callback 上传回调
     */
    public static void uploadAvatar(Context context, int userId, Uri imageUri, UploadCallback callback) {
        uploadAvatar(context, userId, imageUri, new AvatarUploadCallback() {
            @Override
            public void onUploadSuccess(String avatarUrl) {
                if (callback != null) {
                    callback.onSuccess(avatarUrl);
                }
            }
            
            @Override
            public void onUploadError(String error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }
    
    /**
     * 获取用户头像URL（实例方法）
     * @param userId 用户ID
     * @param callback 获取回调
     */
    public void getAvatarUrl(int userId, AvatarUrlCallback callback) {
        executor.execute(() -> {
            try {
                // 模拟网络请求
                Thread.sleep(500); // 模拟网络延迟
                
                // 这里应该是实际的网络请求逻辑
                // 目前返回一个模拟的URL，实际应该从服务器获取
                String avatarUrl = "https://example.com/avatars/user_" + userId + ".jpg";
                
                if (callback != null) {
                    callback.onSuccess(avatarUrl);
                }
                
                Log.d(TAG, "Avatar URL retrieved for user " + userId + ": " + avatarUrl);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to get avatar URL for user " + userId, e);
                if (callback != null) {
                    callback.onError("获取头像失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 删除用户头像
     * @param userId 用户ID
     * @param callback 删除回调
     */
    public static void deleteAvatar(int userId, AvatarUploadCallback callback) {
        executor.execute(() -> {
            try {
                // 模拟删除过程
                Thread.sleep(500);
                
                // 这里应该是实际的删除逻辑
                Log.d(TAG, "Avatar deleted for user " + userId);
                
                if (callback != null) {
                    callback.onUploadSuccess("头像删除成功");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete avatar for user " + userId, e);
                if (callback != null) {
                    callback.onUploadError("删除失败: " + e.getMessage());
                }
            }
        });
    }
}