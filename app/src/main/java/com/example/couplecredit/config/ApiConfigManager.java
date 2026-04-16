package com.example.couplecredit.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import com.example.couplecredit.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * API 配置管理器
 * 管理服务器地址配置，支持运行时动态更新
 */
public class ApiConfigManager {
    private static final String TAG = "ApiConfigManager";
    private static final String PREFS_NAME = "api_config";
    private static final String KEY_CUSTOM_BASE_URL = "custom_base_url";
    private static final String STABLE_BASE_URL = "https://api.datafun.online";

    public interface ConnectionTestCallback {
        void onSuccess(String url);
        void onError(String error);
    }

    /**
     * 获取当前 API 基础 URL
     * 优先使用用户自定义的地址，如果没有则使用 BuildConfig 默认值
     * @param context 上下文
     * @return API 基础 URL
     */
    public static String getBaseUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String customUrl = prefs.getString(KEY_CUSTOM_BASE_URL, null);
        if (isValidCustomUrl(customUrl)) {
            return customUrl.trim();
        }
        if (customUrl != null && !customUrl.trim().isEmpty()) {
            Log.w(TAG, "忽略旧 tunnel 地址，回退到稳定域名: " + customUrl);
            prefs.edit().remove(KEY_CUSTOM_BASE_URL).apply();
        }
        return getDefaultUrl();
    }

    /**
     * 设置自定义 API 基础 URL
     * @param context 上下文
     * @param url 自定义 URL
     */
    public static void setCustomBaseUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, url).apply();
    }

    /**
     * 清除自定义 URL，恢复使用 BuildConfig 默认值
     * @param context 上下文
     */
    public static void clearCustomBaseUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_CUSTOM_BASE_URL).apply();
    }

    /**
     * 检查是否设置了自定义 URL
     * @param context 上下文
     * @return 是否有自定义 URL
     */
    public static boolean hasCustomUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String customUrl = prefs.getString(KEY_CUSTOM_BASE_URL, null);
        return isValidCustomUrl(customUrl);
    }

    /**
     * 获取默认 URL（BuildConfig 中的值）
     * @return 默认 URL
     */
    public static String getDefaultUrl() {
        String buildConfigUrl = BuildConfig.PRIVATE_API_BASE_URL;
        if (buildConfigUrl != null && !buildConfigUrl.trim().isEmpty() && !buildConfigUrl.contains(".lhr.life")) {
            return buildConfigUrl.trim();
        }
        return STABLE_BASE_URL;
    }

    private static boolean isValidCustomUrl(String customUrl) {
        if (customUrl == null || customUrl.trim().isEmpty()) {
            return false;
        }
        return !customUrl.trim().contains(".lhr.life");
    }

    /**
     * 测试服务器连接
     * @param context 上下文
     * @param callback 回调
     */
    public static void testConnection(Context context, ConnectionTestCallback callback) {
        String url = getBaseUrl(context);
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "连接失败";

            @Override
            protected Boolean doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    URL testUrl = new URL(url + "/api/auth/healthz");
                    connection = (HttpURLConnection) testUrl.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);

                    int status = connection.getResponseCode();
                    if (status == 200) {
                        InputStream stream = connection.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                        String response = reader.readLine();
                        reader.close();
                        if (response != null && response.contains("\"ok\":true")) {
                            return true;
                        }
                    }
                    errorMessage = "服务器响应异常 (HTTP " + status + ")";
                    return false;
                } catch (Exception e) {
                    errorMessage = "无法连接服务器: " + e.getMessage();
                    Log.e(TAG, "连接测试失败: " + e.getMessage());
                    return false;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess(url);
                    } else {
                        callback.onError(errorMessage);
                    }
                }
            }
        }.execute();
    }
}