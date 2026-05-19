package com.example.couplecredit.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.couplecredit.R;
import com.example.couplecredit.config.ApiConfigManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class NotificationHelper {

    private static final String TAG = "NotificationHelper";
    private static final String CHANNEL_ID = "app_notifications";
    private static final String CHANNEL_NAME = "应用通知";

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("应用通知");
            channel.enableLights(true);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);
            channel.setSound(null, null);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static void showNotification(Context context, int id, String title, String body, Intent intent) {
        createNotificationChannel(context);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(0)
                .setVibrate(null)
                .setSound(null)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true);

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "No notification permission: " + e.getMessage());
        }
    }

    public static void registerFcmToken(Context context, String token) {
        registerPushToken(context, token, "fcm");
    }

    public static void registerPushToken(Context context, String token, String channel) {
        if (!UserInfoManager.isUserLoggedIn(context)) return;
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId <= 0) return;

        new Thread(() -> {
            try {
                String baseUrl = ApiConfigManager.getBaseUrl(context);
                URL url = new URL(baseUrl + "/api/push/register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                String json = "{\"userId\":" + userId + ",\"token\":\"" + token + "\",\"channel\":\"" + channel + "\"}";
                Log.d(TAG, "Registering " + channel + " token for userId=" + userId);
                OutputStream os = conn.getOutputStream();
                os.write(json.getBytes("UTF-8"));
                os.close();

                int status = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, channel + " token register response status=" + status);
            } catch (Exception e) {
                Log.e(TAG, "Failed to register " + channel + " token: " + e.getMessage());
            }
        }, channel + "TokenRegistrar").start();
    }
}
