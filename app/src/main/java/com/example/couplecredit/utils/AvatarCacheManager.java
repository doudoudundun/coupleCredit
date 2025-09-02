package com.example.couplecredit.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AvatarUploadApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 头像缓存管理器
 * 负责头像的本地缓存、加载和显示
 */
public class AvatarCacheManager {
    
    private static final String TAG = "AvatarCacheManager";
    private static AvatarCacheManager instance;
    
    // 内存缓存
    private LruCache<String, Bitmap> memoryCache;
    
    // 磁盘缓存目录
    private File cacheDir;
    
    private AvatarCacheManager(Context context) {
        // 初始化内存缓存
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8; // 使用1/8的可用内存作为缓存
        
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        
        // 初始化磁盘缓存目录
        cacheDir = new File(context.getCacheDir(), "avatars");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized AvatarCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new AvatarCacheManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 加载并显示用户头像
     * @param context 上下文
     * @param imageView 要显示头像的ImageView
     * @param userId 用户ID
     * @param localUri 本地头像URI（可选）
     */
    public void loadAvatar(Context context, ImageView imageView, int userId, String localUri) {
        String cacheKey = "avatar_" + userId;
        
        // 1. 先检查内存缓存
        Bitmap cachedBitmap = memoryCache.get(cacheKey);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            setImageViewBitmap(context, imageView, cachedBitmap);
            return;
        }
        
        // 2. 检查磁盘缓存
        File cachedFile = new File(cacheDir, cacheKey + ".jpg");
        if (cachedFile.exists()) {
            Bitmap diskBitmap = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
            if (diskBitmap != null) {
                memoryCache.put(cacheKey, diskBitmap);
                setImageViewBitmap(context, imageView, diskBitmap);
                return;
            }
        }
        
        // 3. 如果有本地URI，优先使用本地图片
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(localUri);
                Glide.with(context)
                    .load(uri)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(imageView);
                return;
            } catch (Exception e) {
                Log.w(TAG, "加载本地头像失败: " + e.getMessage());
            }
        }
        
        // 4. 从服务器获取头像
        loadAvatarFromServer(context, imageView, userId, cacheKey);
    }
    
    /**
     * 从服务器加载头像
     */
    private void loadAvatarFromServer(Context context, ImageView imageView, int userId, String cacheKey) {
        // 先显示默认头像
        Glide.with(context)
            .load(R.drawable.ic_default_avatar)
            .transform(new CircleCrop())
            .into(imageView);
        
        // 从服务器获取头像
        AvatarUploadApi.getAvatar(userId, new AvatarUploadApi.AvatarGetCallback() {
            @Override
            public void onAvatarLoaded(String avatarUrl) {
                if (avatarUrl != null && avatarUrl.startsWith("data:image/")) {
                    // 解析Base64图片
                    Bitmap bitmap = decodeBase64Image(avatarUrl);
                    if (bitmap != null) {
                        // 缓存到内存和磁盘
                        memoryCache.put(cacheKey, bitmap);
                        saveBitmapToCache(bitmap, cacheKey);
                        
                        // 在主线程更新UI
                        if (imageView.getContext() instanceof android.app.Activity) {
                            ((android.app.Activity) imageView.getContext()).runOnUiThread(() -> {
                                setImageViewBitmap(context, imageView, bitmap);
                            });
                        }
                    }
                }
            }
            
            @Override
            public void onAvatarError(String error) {
                Log.w(TAG, "从服务器获取头像失败: " + error);
                // 保持显示默认头像
            }
        });
    }
    
    /**
     * 设置ImageView显示Bitmap
     */
    private void setImageViewBitmap(Context context, ImageView imageView, Bitmap bitmap) {
        Glide.with(context)
            .load(bitmap)
            .transform(new CircleCrop())
            .placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
            .into(imageView);
    }
    
    /**
     * 解码Base64图片
     */
    private Bitmap decodeBase64Image(String base64Image) {
        try {
            // 移除data:image/jpeg;base64,前缀
            String base64Data = base64Image.substring(base64Image.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        } catch (Exception e) {
            Log.e(TAG, "解码Base64图片失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 保存Bitmap到磁盘缓存
     */
    private void saveBitmapToCache(Bitmap bitmap, String cacheKey) {
        try {
            File cacheFile = new File(cacheDir, cacheKey + ".jpg");
            FileOutputStream fos = new FileOutputStream(cacheFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
        } catch (IOException e) {
            Log.e(TAG, "保存头像到磁盘缓存失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 清除指定用户的头像缓存
     */
    public void clearUserAvatarCache(int userId) {
        String cacheKey = "avatar_" + userId;
        
        // 清除内存缓存
        memoryCache.remove(cacheKey);
        
        // 清除磁盘缓存
        File cacheFile = new File(cacheDir, cacheKey + ".jpg");
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }
    
    /**
     * 清除所有头像缓存
     */
    public void clearAllCache() {
        // 清除内存缓存
        memoryCache.evictAll();
        
        // 清除磁盘缓存
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
    
    /**
     * 预加载用户头像到缓存
     */
    public void preloadAvatar(int userId) {
        String cacheKey = "avatar_" + userId;
        
        // 如果已经在缓存中，直接返回
        if (memoryCache.get(cacheKey) != null) {
            return;
        }
        
        // 从服务器获取并缓存
        AvatarUploadApi.getAvatar(userId, new AvatarUploadApi.AvatarGetCallback() {
            @Override
            public void onAvatarLoaded(String avatarUrl) {
                if (avatarUrl != null && avatarUrl.startsWith("data:image/")) {
                    Bitmap bitmap = decodeBase64Image(avatarUrl);
                    if (bitmap != null) {
                        memoryCache.put(cacheKey, bitmap);
                        saveBitmapToCache(bitmap, cacheKey);
                    }
                }
            }
            
            @Override
            public void onAvatarError(String error) {
                Log.w(TAG, "预加载头像失败: " + error);
            }
        });
    }
}