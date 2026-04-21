package com.example.couplecredit.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AvatarUploadApi;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.config.DatabaseConfig;

public class AvatarCacheManager {
    private static final String TAG = "AvatarCacheManager";
    private static final String PREF_NAME = "user_avatars";

    private static AvatarCacheManager instance;
    private final Context context;

    private AvatarCacheManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized AvatarCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new AvatarCacheManager(context);
        }
        return instance;
    }

    public void loadAvatar(Context context, ImageView imageView, int userId, String avatarUri) {
        try {
            if (avatarUri != null && !avatarUri.isEmpty()) {
                loadAvatarFromUri(context, imageView, avatarUri);
                cacheAvatarUri(userId, avatarUri);
                return;
            }

            String cachedUri = getCachedAvatarUri(userId);
            if (cachedUri != null && !cachedUri.isEmpty()) {
                loadAvatarFromUri(context, imageView, cachedUri);
                return;
            }

            loadAvatarFromServer(context, imageView, userId);
        } catch (Exception e) {
            Log.e(TAG, "Error loading avatar for user " + userId, e);
            imageView.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    private void loadAvatarFromUri(Context context, ImageView imageView, String uri) {
        String resolved = ApiConfigManager.resolveResourceUrl(context, uri);
        Glide.with(context)
                .load(resolved)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(imageView);
    }

    private void loadAvatarFromServer(Context context, ImageView imageView, int userId) {
        imageView.setImageResource(R.drawable.ic_default_avatar);
        AvatarUploadApi avatarApi = new AvatarUploadApi();
        avatarApi.getAvatarUrl(context, userId, new AvatarUploadApi.AvatarUrlCallback() {
            @Override
            public void onSuccess(String avatarUrl) {
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    cacheAvatarUri(userId, avatarUrl);
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> loadAvatarFromUri(context, imageView, avatarUrl));
                    } else {
                        loadAvatarFromUri(context, imageView, avatarUrl);
                    }
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to get avatar URL for user " + userId + ": " + error);
            }
        });
    }

    private String getCachedAvatarUri(int userId) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(DatabaseConfig.PREF_AVATAR_URI + userId, null);
    }

    private void cacheAvatarUri(int userId, String avatarUri) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, avatarUri).apply();
    }

    public void clearUserAvatarCache(int userId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(DatabaseConfig.PREF_AVATAR_URI + userId).apply();
            Log.d(TAG, "Cleared avatar cache for user " + userId);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing avatar cache for user " + userId, e);
        }
    }

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
