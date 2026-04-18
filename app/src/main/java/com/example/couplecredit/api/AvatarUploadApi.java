package com.example.couplecredit.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AvatarUploadApi {
    private static final String TAG = "AvatarUploadApi";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final int MAX_DIMENSION = 512;
    private static final int JPEG_QUALITY = 80;

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
                InputStream compressed = compressImage(context, imageUri);
                if (compressed == null) {
                    if (callback != null) callback.onUploadError("图片压缩失败");
                    return;
                }

                String fileName = "avatar_" + userId + "_" + System.currentTimeMillis() + ".jpg";
                AuthApiClient.uploadImage(context, compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                    @Override public void onSuccess(String imageUrl) {
                        // Save avatar URL to user profile
                        AuthApiClient.updateAvatar(context, userId, imageUrl, new AuthApiClient.SimpleCallback() {
                            @Override public void onSuccess() {
                                Log.d(TAG, "Avatar uploaded and saved: " + imageUrl);
                                if (callback != null) callback.onUploadSuccess(imageUrl);
                            }
                            @Override public void onError(String e) {
                                Log.w(TAG, "Avatar uploaded but profile update failed: " + e);
                                if (callback != null) callback.onUploadSuccess(imageUrl);
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

    public void getAvatarUrl(int userId, AvatarUrlCallback callback) {
        executor.execute(() -> {
            try {
                Thread.sleep(500);
                String avatarUrl = "https://example.com/avatars/user_" + userId + ".jpg";
                if (callback != null) callback.onSuccess(avatarUrl);
            } catch (Exception e) {
                if (callback != null) callback.onError("获取头像失败: " + e.getMessage());
            }
        });
    }

    private static InputStream compressImage(Context context, Uri imageUri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            is.close();

            int sampleSize = 1;
            int halfW = bounds.outWidth / 2;
            int halfH = bounds.outHeight / 2;
            while ((halfW / sampleSize) >= MAX_DIMENSION && (halfH / sampleSize) >= MAX_DIMENSION) {
                sampleSize *= 2;
            }

            is = context.getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            is.close();
            if (bitmap == null) return null;

            if (bitmap.getWidth() > MAX_DIMENSION || bitmap.getHeight() > MAX_DIMENSION) {
                float scale = Math.min((float) MAX_DIMENSION / bitmap.getWidth(), (float) MAX_DIMENSION / bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bitmap.recycle();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "compressImage failed", e);
            return null;
        }
    }
}
