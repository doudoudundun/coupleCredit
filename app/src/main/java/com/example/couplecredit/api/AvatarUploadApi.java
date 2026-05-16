package com.example.couplecredit.api;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.example.couplecredit.utils.ImageCompressor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AvatarUploadApi {
    private static final String TAG = "AvatarUploadApi";
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public interface AvatarUploadCallback {
        void onUploadSuccess(String avatarUrl);
        void onUploadError(String error);
    }

    public interface UploadCallback {
        void onSuccess(String avatarUrl);
        void onError(String error);
    }

    public interface AvatarUrlCallback {
        void onSuccess(String avatarUrl);
        void onError(String error);
    }

    public static void uploadAvatar(Context context, int userId, Uri imageUri, AvatarUploadCallback callback) {
        executor.execute(() -> {
            try {
                byte[] compressedBytes = ImageCompressor.compress(context, imageUri, 512, 80);
                InputStream compressed = compressedBytes != null ? new ByteArrayInputStream(compressedBytes) : null;
                if (compressed == null) {
                    if (callback != null) callback.onUploadError("图片压缩失败");
                    return;
                }

                String fileName = "avatar_" + userId + "_" + System.currentTimeMillis() + ".jpg";
                AuthApiClient.uploadImage(context, compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                    @Override public void onSuccess(String imageUrl) {
                        AuthApiClient.updateAvatar(context, userId, imageUrl, new AuthApiClient.SimpleCallback() {
                            @Override public void onSuccess() {
                                Log.d(TAG, "Avatar uploaded and saved: " + imageUrl);
                                if (callback != null) callback.onUploadSuccess(imageUrl);
                            }
                            @Override public void onError(String e) {
                                Log.w(TAG, "Avatar uploaded but profile update failed: " + e);
                                if (callback != null) callback.onUploadError("头像资料保存失败: " + e);
                            }
                        });
                    }
                    @Override public void onError(String e) {
                        Log.e(TAG, "Avatar upload failed: " + e);
                        if (callback != null) callback.onUploadError(e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to upload avatar", e);
                if (callback != null) callback.onUploadError("上传失败: " + e.getMessage());
            }
        });
    }

    public static void uploadAvatar(Context context, int userId, Uri imageUri, UploadCallback callback) {
        uploadAvatar(context, userId, imageUri, new AvatarUploadCallback() {
            @Override public void onUploadSuccess(String avatarUrl) {
                if (callback != null) callback.onSuccess(avatarUrl);
            }
            @Override public void onUploadError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public void getAvatarUrl(Context context, int userId, AvatarUrlCallback callback) {
        AuthApiClient.getUserProfile(context, userId, new AuthApiClient.ProfileCallback() {
            @Override
            public void onSuccess(AuthApiModels.UserProfileData profile) {
                if (callback != null) callback.onSuccess(profile != null ? profile.avatarUrl : null);
            }

            @Override
            public void onError(String e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

}
