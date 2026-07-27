package com.example.couplecredit.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.config.ApiConfigManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class PollingManager {
    private static final String TAG = "PollingManager";
    private static final long INTERVAL_MS = 20_000;
    private static final long MAX_NOTIFICATION_AGE_MS = 3_600_000; // 1 hour
    private static final int MAX_SHOWN_IDS = 200;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable currentRefresh;
    private boolean running = false;
    private Context appContext;
    private final Set<Integer> shownNotificationIds = new HashSet<>();

    private static final PollingManager INSTANCE = new PollingManager();

    public static PollingManager getInstance() {
        return INSTANCE;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (currentRefresh != null) {
                try {
                    currentRefresh.run();
                } catch (Exception e) {
                    Log.e(TAG, "Polling refresh error", e);
                }
            }
            pollNotifications();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public void setRefreshAction(Runnable action) {
        currentRefresh = action;
    }

    public void setAppContext(Context context) {
        appContext = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        // 释放注入的 refresh action（它捕获了 Activity 引用，不释放会泄漏 Activity）
        currentRefresh = null;
    }

    private void pollNotifications() {
        if (appContext == null || !UserInfoManager.isUserLoggedIn(appContext)) return;
        int userId = UserInfoManager.getCurrentUserId(appContext);
        if (userId <= 0) return;

        new Thread(() -> {
            try {
                String baseUrl = ApiConfigManager.getBaseUrl(appContext);
                URL url = new URL(baseUrl + "/api/notifications?userId=" + userId + "&limit=5");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                String token = UserInfoManager.getAccessToken(appContext);
                if (token != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    conn.disconnect();
                    return;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                String json = sb.toString();
                handler.post(() -> processNotificationResponse(json));
            } catch (Exception e) {
                Log.d(TAG, "Notification poll failed: " + e.getMessage());
            }
        }, "NotificationPoll").start();
    }

    private void processNotificationResponse(String json) {
        try {
            com.example.couplecredit.api.AuthApiModels.NotificationListResponse response =
                    GSON.fromJson(json, com.example.couplecredit.api.AuthApiModels.NotificationListResponse.class);
            if (response == null || !response.ok || response.data == null || response.data.items == null) return;

            for (com.example.couplecredit.api.AuthApiModels.NotificationItem item : response.data.items) {
                if (item.isRead) continue;
                if (shownNotificationIds.contains(item.notificationId)) continue;
                if (isOlderThanOneHour(item.createdAt)) continue;

                shownNotificationIds.add(item.notificationId);
                if (shownNotificationIds.size() > MAX_SHOWN_IDS) {
                    shownNotificationIds.clear();
                }

                Intent intent = new Intent(appContext, MainActivity.class);
                intent.putExtra("navigate_to", "todo");
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                NotificationHelper.showNotification(appContext, item.notificationId, item.title, item.body, intent);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to parse notification response: " + e.getMessage());
        }
    }

    private boolean isOlderThanOneHour(String createdAt) {
        if (createdAt == null) return false;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            java.util.Date date = sdf.parse(createdAt.substring(0, 19));
            return date != null && (System.currentTimeMillis() - date.getTime()) > MAX_NOTIFICATION_AGE_MS;
        } catch (Exception e) {
            return false;
        }
    }
}
