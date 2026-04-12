package com.example.couplecredit.api;

import android.os.AsyncTask;

import com.example.couplecredit.BuildConfig;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AuthApiClient {
    private static final Gson GSON = new Gson();

    public interface Callback {
        void onSuccess(AuthApiModels.AuthResponse response);
        void onError(String message);
    }

    public interface BillCallback {
        void onSuccess(AuthApiModels.BillResponse response);
        void onError(String message);
    }

    public static void register(String username, String email, String password, String inviteCode, Callback callback) {
        postJson("/api/auth/register", new AuthApiModels.RegisterRequest(username, email, password, inviteCode), callback);
    }

    public static void login(String username, String password, Callback callback) {
        postJson("/api/auth/login", new AuthApiModels.LoginRequest(username, password), callback);
    }

    public static void createBill(int userId, String billOwner, String title, String type, double amount, String date, String time, int incomeType, BillCallback callback) {
        postBillJson("/api/bills", new AuthApiModels.CreateBillRequest(userId, billOwner, title, type, amount, date, time, incomeType), callback);
    }

    private static void postJson(String path, Object body, Callback callback) {
        new AsyncTask<Void, Void, AuthApiModels.AuthResponse>() {
            private String errorMessage = "服务器连接失败，请稍后重试";

            @Override
            protected AuthApiModels.AuthResponse doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(BuildConfig.PRIVATE_API_BASE_URL + path);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setDoOutput(true);

                    try (OutputStream outputStream = connection.getOutputStream();
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                        writer.write(GSON.toJson(body));
                        writer.flush();
                    }

                    int status = connection.getResponseCode();
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String responseText = readText(stream);
                    AuthApiModels.AuthResponse response = GSON.fromJson(responseText, AuthApiModels.AuthResponse.class);
                    if (response != null && response.ok) {
                        return response;
                    }
                    if (response != null && response.error != null && response.error.message != null && !response.error.message.isEmpty()) {
                        errorMessage = response.error.message;
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            protected void onPostExecute(AuthApiModels.AuthResponse response) {
                if (callback == null) {
                    return;
                }
                if (response != null) {
                    callback.onSuccess(response);
                } else {
                    callback.onError(errorMessage);
                }
            }
        }.execute();
    }

    private static void postBillJson(String path, Object body, BillCallback callback) {
        new AsyncTask<Void, Void, AuthApiModels.BillResponse>() {
            private String errorMessage = "服务器连接失败，请稍后重试";

            @Override
            protected AuthApiModels.BillResponse doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(BuildConfig.PRIVATE_API_BASE_URL + path);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setDoOutput(true);

                    try (OutputStream outputStream = connection.getOutputStream();
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                        writer.write(GSON.toJson(body));
                        writer.flush();
                    }

                    int status = connection.getResponseCode();
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String responseText = readText(stream);
                    AuthApiModels.BillResponse response = GSON.fromJson(responseText, AuthApiModels.BillResponse.class);
                    if (response != null && response.ok) {
                        return response;
                    }
                    if (response != null && response.error != null && response.error.message != null && !response.error.message.isEmpty()) {
                        errorMessage = response.error.message;
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            protected void onPostExecute(AuthApiModels.BillResponse response) {
                if (callback == null) {
                    return;
                }
                if (response != null) {
                    callback.onSuccess(response);
                } else {
                    callback.onError(errorMessage);
                }
            }
        }.execute();
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
