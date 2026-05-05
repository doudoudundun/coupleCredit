package com.example.couplecredit.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class DataLocalCache {
    private static final String TAG = "DataLocalCache";
    private static final String PREFS_NAME = "data_local_cache";

    public static void put(Context context, String key, String json) {
        try {
            getPrefs(context).edit().putString(key, json).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write cache: " + key, e);
        }
    }

    public static String get(Context context, String key) {
        try {
            return getPrefs(context).getString(key, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read cache: " + key, e);
            return null;
        }
    }

    public static void remove(Context context, String key) {
        getPrefs(context).edit().remove(key).apply();
    }

    public static void clearAll(Context context) {
        getPrefs(context).edit().clear().apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
