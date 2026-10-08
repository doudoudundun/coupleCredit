package com.example.couplecredit.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
        // 记下本机 token，退出登录时用它调用 DELETE /api/push/token 清理服务端绑定
        context.getSharedPreferences("push_tokens", Context.MODE_PRIVATE)
                .edit()
                .putString("token", token)
                .putString("channel", channel)
                .apply();

        if (!UserInfoManager.isUserLoggedIn(context)) return;
        String accessToken = UserInfoManager.getAccessToken(context);
        if (accessToken == null || accessToken.trim().isEmpty()) return;

        new Thread(() -> {
            try {
                String baseUrl = ApiConfigManager.getBaseUrl(context);
                URL url = new URL(baseUrl + "/api/push/register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                String json = "{\"token\":\"" + token + "\",\"channel\":\"" + channel + "\"}";
                Log.d(TAG, "Registering authenticated " + channel + " token");
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

    /**
     * 退出登录/注销账号时调用：删除服务端的本机推送 token，避免继续向已登出设备推送。
     * 必须在清空本地登录态之前调用（删除接口需要鉴权）。失败不影响退出流程。
     */
    public static void clearRegisteredToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("push_tokens", Context.MODE_PRIVATE);
        String token = prefs.getString("token", null);
        String channel = prefs.getString("channel", "fcm");
        String accessToken = UserInfoManager.getAccessToken(context);
        if (token == null || token.trim().isEmpty()) return;
        if (accessToken == null || accessToken.trim().isEmpty()) return;

        new Thread(() -> {
            try {
                String baseUrl = ApiConfigManager.getBaseUrl(context);
                String query = "?token=" + java.net.URLEncoder.encode(token, "UTF-8")
                        + "&channel=" + java.net.URLEncoder.encode(channel, "UTF-8");
                URL url = new URL(baseUrl + "/api/push/token" + query);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int status = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "Push token deregister response status=" + status);
                if (status >= 200 && status < 300) {
                    prefs.edit().remove("token").remove("channel").apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to deregister push token: " + e.getMessage());
            }
        }, "pushTokenDeregistrar").start();
    }
}
