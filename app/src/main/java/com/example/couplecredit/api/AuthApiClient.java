package com.example.couplecredit.api;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.example.couplecredit.config.ApiConfigManager;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AuthApiClient {
    private static final String TAG = "AuthApiClient";
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final String DEFAULT_ERROR = "服务器连接失败，请稍后重试";

    public interface Callback {
        void onSuccess(AuthApiModels.AuthResponse response);
        void onError(String message);
    }

    public interface BillCallback {
        void onSuccess(AuthApiModels.BillResponse response);
        void onError(String message);
    }

    public interface BillsQueryCallback {
        void onSuccess(AuthApiModels.BillsQueryResponse response);
        void onError(String message);
    }

    public interface InventoryListCallback {
        void onSuccess(AuthApiModels.InventoryListResponse response);
        void onError(String message);
    }

    public interface DeleteBillCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface UpdateBillCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface InventoryMutationCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface ImageUploadCallback {
        void onSuccess(String imageUrl);
        void onError(String message);
    }

    public interface CoupleInfoCallback {
        void onCoupleFound(int partnerId, String partnerName, String partnerNickname, int relationshipId);
        void onNoCoupleFound();
        void onError(String message);
    }

    private interface RawCallback {
        void onSuccess(String json);
        void onError(String message);
    }

    private static class RequestResult {
        final boolean success;
        final String text;
        final int statusCode;

        RequestResult(boolean success, String text, int statusCode) {
            this.success = success;
            this.text = text;
            this.statusCode = statusCode;
        }
    }

    public static void register(Context context, String username, String email, String password, String inviteCode, Callback callback) {
        doRequest(context, "POST", "/api/auth/register",
                GSON.toJson(new AuthApiModels.RegisterRequest(username, email, password, inviteCode)),
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.AuthResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.AuthResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "register 响应解析失败: " + json, e);
                                callback.onError(buildParseError("注册", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void login(Context context, String username, String password, Callback callback) {
        doRequest(context, "POST", "/api/auth/login",
                GSON.toJson(new AuthApiModels.LoginRequest(username, password)),
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.AuthResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.AuthResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "login 响应解析失败: " + json, e);
                                callback.onError(buildParseError("登录", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void createBill(Context context, int userId, String billOwner, String title, String type, double amount, String date, String time, int incomeType, BillCallback callback) {
        doRequest(context, "POST", "/api/bills",
                GSON.toJson(new AuthApiModels.CreateBillRequest(userId, billOwner, title, type, amount, date, time, incomeType)),
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.BillResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BillResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "createBill 响应解析失败: " + json, e);
                                callback.onError(buildParseError("账单", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void queryBills(Context context, int userId, int year, int month, BillsQueryCallback callback) {
        doRequest(context, "GET", "/api/bills?userId=" + userId + "&year=" + year + "&month=" + month,
                null,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.BillsQueryResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BillsQueryResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "queryBills 响应解析失败: " + json, e);
                                callback.onError(buildParseError("账单查询", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void queryInventory(Context context, int userId, InventoryListCallback callback) {
        doRequest(context, "GET", "/api/inventory?userId=" + userId,
                null,
                inventoryListCallback("物资列表查询", callback));
    }

    public static void queryLowStockInventory(Context context, int userId, InventoryListCallback callback) {
        doRequest(context, "GET", "/api/inventory/low-stock?userId=" + userId,
                null,
                inventoryListCallback("告急物资查询", callback));
    }

    public static void createInventory(Context context, AuthApiModels.CreateInventoryRequest request, InventoryMutationCallback callback) {
        doRequest(context, "POST", "/api/inventory",
                GSON.toJson(request),
                simpleMutationCallback("新增物资", callback));
    }

    public static void updateInventory(Context context, int inventoryId, AuthApiModels.UpdateInventoryRequest request, InventoryMutationCallback callback) {
        doRequest(context, "PUT", "/api/inventory/" + inventoryId,
                GSON.toJson(request),
                simpleMutationCallback("更新物资", callback));
    }

    public static void consumeInventory(Context context, int inventoryId, int userId, double consumeAmount, InventoryMutationCallback callback) {
        doRequest(context, "POST", "/api/inventory/" + inventoryId + "/consume",
                GSON.toJson(new AuthApiModels.InventoryAmountRequest(userId, consumeAmount, 0)),
                simpleMutationCallback("消耗物资", callback));
    }

    public static void replenishInventory(Context context, int inventoryId, int userId, double addAmount, InventoryMutationCallback callback) {
        doRequest(context, "POST", "/api/inventory/" + inventoryId + "/replenish",
                GSON.toJson(new AuthApiModels.InventoryAmountRequest(userId, 0, addAmount)),
                simpleMutationCallback("补货", callback));
    }

    public static void deleteInventory(Context context, int inventoryId, int userId, InventoryMutationCallback callback) {
        doRequest(context, "DELETE", "/api/inventory/" + inventoryId + "?userId=" + userId,
                null,
                simpleMutationCallback("删除物资", callback));
    }

    public static void queryCoupleInfo(Context context, int userId, CoupleInfoCallback callback) {
        doRequest(context, "GET", "/api/auth/couple-info?userId=" + userId,
                null,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback == null) return;
                        try {
                            com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                            if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                                com.google.gson.JsonObject data = obj.getAsJsonObject("data");
                                boolean hasCouple = data.has("hasCouple") && data.get("hasCouple").getAsBoolean();
                                if (hasCouple) {
                                    int partnerId = data.get("partnerId").getAsInt();
                                    String partnerName = data.get("partnerName").getAsString();
                                    String partnerNickname = data.has("partnerNickname") && !data.get("partnerNickname").isJsonNull() ? data.get("partnerNickname").getAsString() : null;
                                    int relationshipId = data.get("relationshipId").getAsInt();
                                    callback.onCoupleFound(partnerId, partnerName, partnerNickname, relationshipId);
                                } else {
                                    callback.onNoCoupleFound();
                                }
                            } else {
                                callback.onError(extractError(null, json));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "couple-info 响应解析失败: " + json, e);
                            callback.onError("情侣信息查询失败");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void uploadImage(Context context, InputStream imageStream, String fileName, ImageUploadCallback callback) {
        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    String boundary = "----UploadBoundary" + System.currentTimeMillis();
                    URL url = new URL(ApiConfigManager.getBaseUrl(context) + "/api/upload/image");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    connection.setRequestProperty("Connection", "keep-alive");
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(15000);

                    try (DataOutputStream dos = new DataOutputStream(connection.getOutputStream())) {
                        // part header
                        dos.writeBytes("--" + boundary + "\r\n");
                        dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"" + fileName + "\"\r\n");
                        dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");

                        // file data
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = imageStream.read(buffer)) != -1) {
                            dos.write(buffer, 0, bytesRead);
                        }
                        dos.writeBytes("\r\n--" + boundary + "--\r\n");
                        dos.flush();
                    }

                    int status = connection.getResponseCode();
                    InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String responseText = readText(responseStream);

                    if (status >= 200 && status < 300) {
                        return new String[]{"ok", responseText};
                    } else {
                        return new String[]{"error", "上传失败: HTTP " + status};
                    }
                } catch (Exception e) {
                    Log.e(TAG, "图片上传失败", e);
                    return new String[]{"error", "图片上传失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误")};
                } finally {
                    if (connection != null) connection.disconnect();
                    try { imageStream.close(); } catch (Exception ignored) {}
                }
            }

            @Override
            protected void onPostExecute(String[] result) {
                if (callback == null) return;
                if ("ok".equals(result[0])) {
                    try {
                        String json = normalizeJsonPayload(result[1]);
                        com.google.gson.JsonObject obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
                        String imageUrl = obj.has("data") && obj.getAsJsonObject("data").has("imageUrl")
                                ? obj.getAsJsonObject("data").get("imageUrl").getAsString() : null;
                        if (imageUrl != null) {
                            callback.onSuccess(imageUrl);
                        } else {
                            callback.onError("上传响应解析失败");
                        }
                    } catch (Exception e) {
                        callback.onError("上传响应解析失败");
                    }
                } else {
                    callback.onError(result[1]);
                }
            }
        }.execute();
    }

    public static void deleteBill(Context context, int billId, int userId, DeleteBillCallback callback) {
        doRequest(context, "DELETE", "/api/bills/" + billId + "?userId=" + userId,
                null,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.SimpleResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.SimpleResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess();
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "deleteBill 响应解析失败: " + json, e);
                                callback.onError(buildParseError("删除账单", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void updateBill(Context context, int billId, int userId, String title, String type, double amount, String date, String time, Integer incomeType, UpdateBillCallback callback) {
        AuthApiModels.UpdateBillRequest req = new AuthApiModels.UpdateBillRequest(userId, title, type, amount, date, time, incomeType);
        doRequest(context, "PUT", "/api/bills/" + billId,
                GSON.toJson(req),
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.SimpleResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.SimpleResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess();
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "updateBill 响应解析失败: " + json, e);
                                callback.onError(buildParseError("更新账单", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    private static RawCallback inventoryListCallback(String operation, InventoryListCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.InventoryListResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.InventoryListResponse.class);
                        if (response != null && response.ok) {
                            callback.onSuccess(response);
                        } else {
                            callback.onError(extractError(response != null ? response.error : null, json));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, operation + "响应解析失败: " + json, e);
                        callback.onError(buildParseError(operation, json));
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        };
    }

    private static RawCallback simpleMutationCallback(String operation, InventoryMutationCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.SimpleResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.SimpleResponse.class);
                        if (response != null && response.ok) {
                            callback.onSuccess();
                        } else {
                            callback.onError(extractError(response != null ? response.error : null, json));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, operation + "响应解析失败: " + json, e);
                        callback.onError(buildParseError(operation, json));
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        };
    }

    private static String extractError(AuthApiModels.ErrorBody error, String rawBody) {
        if (error != null && error.message != null && !error.message.isEmpty()) {
            return error.message;
        }
        String fallback = sanitizeRawBody(rawBody);
        if (!fallback.isEmpty()) {
            return fallback;
        }
        return DEFAULT_ERROR;
    }

    private static String normalizeJsonPayload(String rawBody) {
        if (rawBody == null) {
            throw new IllegalStateException("响应为空");
        }

        String trimmed = rawBody.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("响应为空");
        }

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        if (trimmed.startsWith("\"")) {
            String nested = JsonParser.parseString(trimmed).getAsString().trim();
            if (nested.startsWith("{") || nested.startsWith("[")) {
                return nested;
            }
            throw new IllegalStateException("响应不是 JSON 对象: " + nested);
        }

        throw new IllegalStateException("响应不是 JSON 对象: " + trimmed);
    }

    private static String buildParseError(String operation, String rawBody) {
        String sanitized = sanitizeRawBody(rawBody);
        if (sanitized.isEmpty()) {
            return operation + "响应解析失败";
        }
        return operation + "响应异常: " + sanitized;
    }

    private static String sanitizeRawBody(String rawBody) {
        if (rawBody == null) {
            return "";
        }

        String trimmed = rawBody.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        trimmed = trimmed.replace('\n', ' ').replace('\r', ' ');
        if (trimmed.length() > 60) {
            return trimmed.substring(0, 60) + "...";
        }
        return trimmed;
    }

    private static void doRequest(Context context, String method, String path, String bodyJson, RawCallback callback) {
        new AsyncTask<Void, Void, RequestResult>() {
            @Override
            protected RequestResult doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(ApiConfigManager.getBaseUrl(context) + path);
                    long startMs = System.currentTimeMillis();
                    Log.d(TAG, "发起请求: " + method + " " + path);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod(method);
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setRequestProperty("Connection", "keep-alive");
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);

                    if (bodyJson != null) {
                        connection.setDoOutput(true);
                        try (OutputStream os = connection.getOutputStream();
                             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                            writer.write(bodyJson);
                            writer.flush();
                        }
                    }

                    int status = connection.getResponseCode();
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String responseText = readText(stream);
                    long elapsed = System.currentTimeMillis() - startMs;
                    Log.d(TAG, "请求完成: " + method + " " + path + " " + elapsed + "ms status=" + status);
                    if (status >= 200 && status < 300) {
                        return new RequestResult(true, responseText, status);
                    }
                    return new RequestResult(false, responseText.isEmpty() ? ("HTTP " + status) : responseText, status);
                } catch (Exception e) {
                    Log.e(TAG, "请求失败: " + method + " " + path, e);
                    String error = e.getMessage();
                    if (error == null || error.trim().isEmpty()) {
                        error = DEFAULT_ERROR;
                    }
                    return new RequestResult(false, error, -1);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            protected void onPostExecute(RequestResult result) {
                if (callback == null) return;
                if (result.success) {
                    callback.onSuccess(result.text);
                } else {
                    callback.onError(result.text);
                }
            }
        }.execute();
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) return "";
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
