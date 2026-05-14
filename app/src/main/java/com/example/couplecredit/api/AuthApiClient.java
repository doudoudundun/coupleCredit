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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public interface BeadInventoryListCallback {
        void onSuccess(AuthApiModels.BeadInventoryListResponse response);
        void onError(String message);
    }

    public interface BeadSettingsCallback {
        void onSuccess(AuthApiModels.BeadSettingsResponse response);
        void onError(String message);
    }

    public interface BeadBlueprintListCallback {
        void onSuccess(AuthApiModels.BeadBlueprintListResponse response);
        void onError(String message);
    }

    public interface BeadBlueprintDetailCallback {
        void onSuccess(AuthApiModels.BeadBlueprintResponse response);
        void onError(String message);
    }

    public interface BeadBlueprintCreateCallback {
        void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response);
        void onError(String message);
    }

    public interface BeadRecognizeColorsCallback {
        void onSuccess(AuthApiModels.BeadRecognizeColorsResponse response);
        void onError(String message);
    }

    public interface BuildBeadBlueprintCallback {
        void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response);
        void onError(String message);
    }

    public interface DeleteBillCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface BeadMutationCallback {
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

    public interface GenerateImageCallback {
        void onSuccess(String imageUrl);
        void onError(String message);
    }

    public interface CoupleInfoCallback {
        void onCoupleFound(int partnerId, String partnerName, String partnerNickname, String partnerAvatarUrl, int relationshipId);
        void onNoCoupleFound();
        void onError(String message);
    }

    public interface RecipeListCallback {
        void onSuccess(AuthApiModels.RecipeListResponse response);
        void onError(String message);
    }

    public interface RecipeDetailCallback {
        void onSuccess(AuthApiModels.RecipeDetailResponse response);
        void onError(String message);
    }

    public interface RecipeMutationCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface CookCallback {
        void onSuccess(AuthApiModels.CookResponse response);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface RecipeCategoryListCallback {
        void onSuccess(AuthApiModels.RecipeCategoryListResponse response);
        void onError(String message);
    }

    public interface SharedPlanListCallback {
        void onSuccess(AuthApiModels.SharedPlanListResponse response);
        void onError(String message);
    }

    public interface SharedPlanMutationCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface TodoListCallback {
        void onSuccess(AuthApiModels.TodoListResponse response);
        void onError(String message);
    }

    public interface RestaurantListCallback {
        void onSuccess(AuthApiModels.RestaurantListResponse response);
        void onError(String message);
    }

    public interface RestaurantMutationCallback {
        void onSuccess();
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
        createBill(context, userId, billOwner, null, title, type, amount, date, time, incomeType, callback);
    }

    public static void createBill(Context context, int userId, String billOwner, Integer sharedPlanId, String title, String type, double amount, String date, String time, int incomeType, BillCallback callback) {
        doRequest(context, "POST", "/api/bills",
                GSON.toJson(new AuthApiModels.CreateBillRequest(userId, billOwner, sharedPlanId, title, type, amount, date, time, incomeType)),
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

    public static void queryBeadInventory(Context context, int userId, BeadInventoryListCallback callback) {
        doRequest(context, "GET", "/api/beads/inventory?userId=" + userId,
                null,
                beadInventoryListCallback("串珠库存查询", callback));
    }

    public static void queryBeadSettings(Context context, int userId, BeadSettingsCallback callback) {
        doRequest(context, "GET", "/api/beads/settings?userId=" + userId,
                null,
                beadSettingsCallback("串珠设置查询", callback));
    }

    public static void updateBeadInventory(Context context, String colorCode, AuthApiModels.UpdateBeadInventoryRequest request, BeadMutationCallback callback) {
        doRequest(context, "PUT", "/api/beads/inventory/" + colorCode,
                GSON.toJson(request),
                beadMutationCallback("更新串珠库存", callback));
    }

    @Deprecated
    public static void updateBeadInventory(Context context, String colorCode, AuthApiModels.BeadInventoryUpdateRequest request, BeadMutationCallback callback) {
        updateBeadInventory(context, colorCode, (AuthApiModels.UpdateBeadInventoryRequest) request, callback);
    }

    public static void updateBeadSettings(Context context, AuthApiModels.BeadSettingsUpdateRequest request, BeadMutationCallback callback) {
        doRequest(context, "PUT", "/api/beads/settings",
                GSON.toJson(request),
                beadMutationCallback("更新串珠设置", callback));
    }

    public static void consumeBeadInventory(Context context, String colorCode, int userId, int consumeAmount, BeadMutationCallback callback) {
        doRequest(context, "POST", "/api/beads/inventory/" + colorCode + "/consume",
                GSON.toJson(new AuthApiModels.BeadInventoryAmountRequest(userId, consumeAmount, null)),
                beadMutationCallback("消耗串珠库存", callback));
    }

    public static void replenishBeadInventory(Context context, String colorCode, int userId, int addAmount, BeadMutationCallback callback) {
        doRequest(context, "POST", "/api/beads/inventory/" + colorCode + "/replenish",
                GSON.toJson(new AuthApiModels.BeadInventoryAmountRequest(userId, null, addAmount)),
                beadMutationCallback("补充串珠库存", callback));
    }

    public static void queryBeadBlueprints(Context context, int userId, BeadBlueprintListCallback callback) {
        doRequest(context, "GET", "/api/beads/blueprints?userId=" + userId,
                null,
                beadBlueprintListCallback("串珠图纸列表查询", callback));
    }

    public static void getBeadBlueprintDetail(Context context, int blueprintId, int userId, BeadBlueprintDetailCallback callback) {
        doRequest(context, "GET", "/api/beads/blueprints/" + blueprintId + "?userId=" + userId,
                null,
                beadBlueprintDetailCallback("串珠图纸详情查询", callback));
    }

    public static void createBeadBlueprint(Context context, AuthApiModels.CreateBeadBlueprintRequest request, BeadBlueprintCreateCallback callback) {
        doRequest(context, "POST", "/api/beads/blueprints",
                GSON.toJson(request),
                beadBlueprintCreateCallback("创建串珠图纸", callback));
    }

    public static void updateBeadBlueprint(Context context, int blueprintId, AuthApiModels.UpdateBeadBlueprintRequest request, BeadMutationCallback callback) {
        doRequest(context, "PUT", "/api/beads/blueprints/" + blueprintId,
                GSON.toJson(request),
                beadMutationCallback("更新串珠图纸", callback));
    }

    public static void deleteBeadBlueprint(Context context, int blueprintId, int userId, BeadMutationCallback callback) {
        doRequest(context, "DELETE", "/api/beads/blueprints/" + blueprintId + "?userId=" + userId,
                null,
                beadMutationCallback("删除串珠图纸", callback));
    }

    public static void buildBeadBlueprint(Context context, int blueprintId, int userId, Integer count, BuildBeadBlueprintCallback callback) {
        doRequest(context, "POST", "/api/beads/blueprints/" + blueprintId + "/build",
                GSON.toJson(new AuthApiModels.BuildBeadBlueprintRequest(userId, count)),
                buildBeadBlueprintCallback("记录串珠制作", callback));
    }

    public static void batchDeductInventory(Context context, int userId, List<AuthApiModels.BatchDeductItem> items, BeadMutationCallback callback) {
        doRequest(context, "POST", "/api/beads/inventory/batch-deduct",
                GSON.toJson(new AuthApiModels.BatchDeductRequest(userId, items)),
                beadMutationCallback("批量扣减库存", callback));
    }

    public interface AiAnalyzeCallback {
        void onSuccess(AuthApiModels.AiAnalyzeResponse response);
        void onError(String error);
    }

    public static void analyzeAiChat(Context context, int userId, List<AuthApiModels.AiChatMessage> messages, AiAnalyzeCallback callback) {
        doRequest(context, "POST", "/api/ai-chat/analyze",
                GSON.toJson(new AuthApiModels.AiAnalyzeRequest(userId, messages)),
                30000, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.AiAnalyzeResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.AiAnalyzeResponse.class);
                        if (response != null && response.ok) callback.onSuccess(response);
                        else callback.onError(extractError(response != null ? response.error : null, json));
                    } catch (Exception e) {
                        callback.onError("解析失败");
                    }
                }
            }
            @Override public void onError(String message) { if (callback != null) callback.onError(message); }
        });
    }

    public static void confirmAiExtraction(Context context, int extractionId, int userId, Map<String, Object> overrides, ConfirmCallback callback) {
        String path = "/api/ai-chat/extractions/" + extractionId + "/confirm";
        doRequest(context, "POST", path, GSON.toJson(new AuthApiModels.AiExtractionActionRequest(userId, overrides)),
                new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.AiExtractionActionResponse resp = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.AiExtractionActionResponse.class);
                        if (resp != null && resp.ok) callback.onSuccess(resp);
                        else callback.onError(resp != null && resp.error != null ? resp.error.message : "确认失败");
                    } catch (Exception e) { callback.onError("解析失败"); }
                }
            }
            @Override public void onError(String message) { if (callback != null) callback.onError(message); }
        });
    }

    public static void dismissAiExtraction(Context context, int extractionId, int userId, ConfirmCallback callback) {
        doRequest(context, "POST", "/api/ai-chat/extractions/" + extractionId + "/dismiss",
                GSON.toJson(new AuthApiModels.AiExtractionActionRequest(userId)),
                new RawCallback() {
            @Override public void onSuccess(String json) { if (callback != null) callback.onSuccess(null); }
            @Override public void onError(String message) { if (callback != null) callback.onError(message); }
        });
    }

    public interface ConfirmCallback {
        void onSuccess(AuthApiModels.AiExtractionActionResponse response);
        void onError(String error);
    }

    private static final int AI_REQUEST_TIMEOUT_MS = 120000;

    public static void recognizeBeadColors(Context context, String imageUrl, BeadRecognizeColorsCallback callback) {
        doRequest(context, "POST", "/api/beads/recognize-colors",
                GSON.toJson(new AuthApiModels.BeadRecognizeColorsRequest(imageUrl)),
                AI_REQUEST_TIMEOUT_MS,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.BeadRecognizeColorsResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadRecognizeColorsResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "AI识图响应解析失败: " + json, e);
                                callback.onError(buildParseError("AI识图", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public interface BeadConvertCallback {
        void onSuccess(AuthApiModels.BeadConvertResponse response);
        void onError(String error);
    }

    public static void convertToBeadImage(Context context, String imageUrl, Integer cols, BeadConvertCallback callback) {
        convertToBeadImage(context, imageUrl, cols, null, null, null, callback);
    }

    public static void convertToBeadImage(Context context, String imageUrl, Integer cols, Integer rows, Integer matchThreshold, Integer smoothExtra, BeadConvertCallback callback) {
        doRequest(context, "POST", "/api/beads/convert-to-bead",
                GSON.toJson(new AuthApiModels.BeadConvertRequest(imageUrl, cols, rows, null, false, matchThreshold, smoothExtra)),
                AI_REQUEST_TIMEOUT_MS,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback != null) {
                            try {
                                AuthApiModels.BeadConvertResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadConvertResponse.class);
                                if (response != null && response.ok) {
                                    callback.onSuccess(response);
                                } else {
                                    callback.onError(extractError(response != null ? response.error : null, json));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "转拼豆响应解析失败: " + json, e);
                                callback.onError(buildParseError("转拼豆", json));
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
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
                                    String partnerAvatarUrl = data.has("partnerAvatarUrl") && !data.get("partnerAvatarUrl").isJsonNull() ? data.get("partnerAvatarUrl").getAsString() : null;
                                    int relationshipId = data.get("relationshipId").getAsInt();
                                    callback.onCoupleFound(partnerId, partnerName, partnerNickname, partnerAvatarUrl, relationshipId);
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
                    connection.setReadTimeout(AI_REQUEST_TIMEOUT_MS);

                    try (DataOutputStream dos = new DataOutputStream(connection.getOutputStream())) {
                        dos.writeBytes("--" + boundary + "\r\n");
                        dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"" + fileName + "\"\r\n");
                        dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");

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

    public static void generateInventoryImage(Context context, String prompt, String category, String name, GenerateImageCallback callback) {
        try {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            if (prompt != null && !prompt.isEmpty()) body.addProperty("prompt", prompt);
            if (category != null && !category.isEmpty()) body.addProperty("category", category);
            if (name != null && !name.isEmpty()) body.addProperty("name", name);
            doRequest(context, "POST", "/api/inventory/generate-image",
                    GSON.toJson(body), AI_REQUEST_TIMEOUT_MS,
                    new RawCallback() {
                        @Override
                        public void onSuccess(String json) {
                            if (callback != null) {
                                try {
                                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                                        com.google.gson.JsonObject data = obj.getAsJsonObject("data");
                                        String imageUrl = data.has("imageUrl") && !data.get("imageUrl").isJsonNull()
                                                ? data.get("imageUrl").getAsString() : null;
                                        if (imageUrl != null) {
                                            callback.onSuccess(imageUrl);
                                        } else {
                                            callback.onError("AI 生图未返回图片");
                                        }
                                    } else {
                                        callback.onError(extractError(null, json));
                                    }
                                } catch (Exception e) {
                                    callback.onError(buildParseError("AI生图", json));
                                }
                            }
                        }
                        @Override
                        public void onError(String message) {
                            if (callback != null) callback.onError(message);
                        }
                    });
        } catch (Exception e) {
            if (callback != null) callback.onError("请求构建失败");
        }
    }

    public static void queryRecipes(Context context, int userId, RecipeListCallback callback) {
        doRequest(context, "GET", "/api/recipes?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.RecipeListResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.RecipeListResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r);
                    else callback.onError(extractError(r != null ? r.error : null, json));
                } catch (Exception e) { callback.onError(buildParseError("菜谱列表", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void getRecipeDetail(Context context, int recipeId, int userId, RecipeDetailCallback callback) {
        doRequest(context, "GET", "/api/recipes/" + recipeId + "?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.RecipeDetailResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.RecipeDetailResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r);
                    else callback.onError(extractError(r != null ? r.error : null, json));
                } catch (Exception e) { callback.onError(buildParseError("菜谱详情", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void createRecipe(Context context, AuthApiModels.CreateRecipeRequest req, RecipeMutationCallback callback) {
        doRequest(context, "POST", "/api/recipes", GSON.toJson(req), simpleMutationCallback("创建菜谱", callback));
    }

    public static void updateRecipe(Context context, int recipeId, AuthApiModels.UpdateRecipeRequest req, RecipeMutationCallback callback) {
        doRequest(context, "PUT", "/api/recipes/" + recipeId, GSON.toJson(req), simpleMutationCallback("更新菜谱", callback));
    }

    public static void deleteRecipe(Context context, int recipeId, int userId, RecipeMutationCallback callback) {
        doRequest(context, "DELETE", "/api/recipes/" + recipeId + "?userId=" + userId, null, simpleMutationCallback("删除菜谱", callback));
    }

    public static void cookRecipe(Context context, int recipeId, int userId, CookCallback callback) {
        doRequest(context, "POST", "/api/recipes/" + recipeId + "/cook",
                GSON.toJson(new AuthApiModels.CookRequest(userId)), new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.CookResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.CookResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r);
                    else callback.onError("烹饪失败");
                } catch (Exception e) { callback.onError(buildParseError("烹饪", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void queryRecipeCategories(Context context, int userId, RecipeCategoryListCallback callback) {
        doRequest(context, "GET", "/api/recipe-categories?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.RecipeCategoryListResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.RecipeCategoryListResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r);
                    else callback.onError(extractError(r != null ? r.error : null, json));
                } catch (Exception e) { callback.onError(buildParseError("菜谱种类", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void createRecipeCategory(Context context, int userId, String name, RecipeMutationCallback callback) {
        String body = "{\"userId\":" + userId + ",\"name\":" + GSON.toJson(name) + "}";
        doRequest(context, "POST", "/api/recipe-categories", body, simpleMutationCallback("创建种类", callback));
    }

    public static void updateRecipeCategory(Context context, int categoryId, int userId, String name, Integer sortOrder, RecipeMutationCallback callback) {
        StringBuilder body = new StringBuilder();
        body.append("{\"userId\":").append(userId);
        if (name != null) {
            body.append(",\"name\":").append(GSON.toJson(name));
        }
        if (sortOrder != null) {
            body.append(",\"sortOrder\":").append(sortOrder);
        }
        body.append("}");
        doRequest(context, "PUT", "/api/recipe-categories/" + categoryId, body.toString(), simpleMutationCallback("更新种类", callback));
    }

    public static void reorderRecipeCategories(Context context, int userId, List<Integer> orderedCategoryIds, RecipeMutationCallback callback) {
        String body = "{\"userId\":" + userId + ",\"orderedCategoryIds\":" + GSON.toJson(orderedCategoryIds) + "}";
        doRequest(context, "PUT", "/api/recipe-categories/reorder/all", body, simpleMutationCallback("排序种类", callback));
    }

    public static void deleteRecipeCategory(Context context, int categoryId, int userId, RecipeMutationCallback callback) {
        doRequest(context, "DELETE", "/api/recipe-categories/" + categoryId + "?userId=" + userId, null, simpleMutationCallback("删除种类", callback));
    }

    public static void queryRestaurants(Context context, int userId, RestaurantListCallback callback) {
        doRequest(context, "GET", "/api/restaurants?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.RestaurantListResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.RestaurantListResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r);
                    else callback.onError(extractError(r != null ? r.error : null, json));
                } catch (Exception e) { callback.onError(buildParseError("商家列表", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void createRestaurant(Context context, AuthApiModels.RestaurantRequest req, RestaurantMutationCallback callback) {
        doRequest(context, "POST", "/api/restaurants", GSON.toJson(req), simpleMutationCallback("添加商家", callback));
    }

    public static void updateRestaurant(Context context, int restaurantId, AuthApiModels.RestaurantRequest req, RestaurantMutationCallback callback) {
        doRequest(context, "PUT", "/api/restaurants/" + restaurantId, GSON.toJson(req), simpleMutationCallback("更新商家", callback));
    }

    public static void deleteRestaurant(Context context, int restaurantId, int userId, RestaurantMutationCallback callback) {
        doRequest(context, "DELETE", "/api/restaurants/" + restaurantId + "?userId=" + userId, null, simpleMutationCallback("删除商家", callback));
    }

    public interface ProfileCallback {
        void onSuccess(AuthApiModels.UserProfileData profile);
        void onError(String message);
    }

    public static void getUserProfile(Context context, int userId, ProfileCallback callback) {
        doRequest(context, "GET", "/api/auth/profile?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.UserProfileResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.UserProfileResponse.class);
                    if (r != null && r.ok && r.data != null) callback.onSuccess(r.data);
                    else callback.onError(extractError(r != null ? r.error : null, json));
                } catch (Exception e) { callback.onError(buildParseError("用户信息", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void resolveUsername(Context context, String username, SimpleIdCallback callback) {
        doRequest(context, "GET", "/api/auth/resolve?username=" + username, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        int userId = obj.getAsJsonObject("data").get("userId").getAsInt();
                        callback.onSuccess(userId);
                    } else {
                        callback.onError("用户不存在");
                    }
                } catch (Exception e) { callback.onError(buildParseError("解析用户名", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public interface SimpleIdCallback {
        void onSuccess(int id);
        void onError(String message);
    }

    public static void updateNickname(Context context, int userId, String nickname, SimpleCallback callback) {
        String body = "{\"userId\":" + userId + ",\"nickname\":" + GSON.toJson(nickname) + "}";
        doRequest(context, "PUT", "/api/auth/nickname", body, fireAndForgetCallback(callback));
    }

    public static void changePassword(Context context, int userId, String currentPassword, String newPassword, SimpleCallback callback) {
        String body = "{\"userId\":" + userId + ",\"currentPassword\":" + GSON.toJson(currentPassword) + ",\"newPassword\":" + GSON.toJson(newPassword) + "}";
        doRequest(context, "PUT", "/api/auth/password", body, fireAndForgetCallback(callback));
    }

    public static void deleteAccount(Context context, int userId, SimpleCallback callback) {
        doRequest(context, "DELETE", "/api/auth/account?userId=" + userId, null, fireAndForgetCallback(callback));
    }

    public interface CoupleRoleCallback {
        void onResult(boolean hasRelationship, int role, int relationshipId);
        void onError(String message);
    }

    public static void getCoupleRole(Context context, int userId, CoupleRoleCallback callback) {
        doRequest(context, "GET", "/api/couple/role?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        com.google.gson.JsonObject data = obj.getAsJsonObject("data");
                        boolean has = data.get("hasRelationship").getAsBoolean();
                        if (has) {
                            callback.onResult(true, data.get("role").getAsInt(), data.get("relationshipId").getAsInt());
                        } else {
                            callback.onResult(false, 0, 0);
                        }
                    } else {
                        callback.onError("获取角色失败");
                    }
                } catch (Exception e) { callback.onError(buildParseError("情侣角色", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void getRelationshipId(Context context, int userId, SimpleIdCallback callback) {
        doRequest(context, "GET", "/api/couple/relationship-id?userId=" + userId, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        com.google.gson.JsonObject data = obj.getAsJsonObject("data");
                        if (data.has("relationshipId") && !data.get("relationshipId").isJsonNull()) {
                            callback.onSuccess(data.get("relationshipId").getAsInt());
                        } else {
                            callback.onSuccess(0);
                        }
                    } else {
                        callback.onError("获取关系ID失败");
                    }
                } catch (Exception e) { callback.onError(buildParseError("关系ID", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public interface InviteCodeCallback {
        void onSuccess(String inviteCode);
        void onError(String message);
    }

    public static void generateInviteCode(Context context, int userId, InviteCodeCallback callback) {
        doRequest(context, "POST", "/api/couple/generate-invite",
                "{\"userId\":" + userId + "}", new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        String code = obj.getAsJsonObject("data").get("inviteCode").getAsString();
                        callback.onSuccess(code);
                    } else {
                        callback.onError(extractError(null, json));
                    }
                } catch (Exception e) { callback.onError(buildParseError("生成邀请码", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public interface UserSearchCallback {
        void onFound(int userId, String username, String nickname);
        void onError(String message);
    }

    public static void searchByInviteCode(Context context, String inviteCode, UserSearchCallback callback) {
        doRequest(context, "GET", "/api/couple/search?inviteCode=" + inviteCode, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        com.google.gson.JsonObject data = obj.getAsJsonObject("data");
                        callback.onFound(data.get("userId").getAsInt(), data.get("username").getAsString(), data.get("nickname").getAsString());
                    } else {
                        callback.onError(extractError(null, json));
                    }
                } catch (Exception e) { callback.onError(buildParseError("搜索用户", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void bindCouple(Context context, int inviterId, int inviteeId, SimpleIdCallback callback) {
        doRequest(context, "POST", "/api/couple/bind",
                "{\"inviterId\":" + inviterId + ",\"inviteeId\":" + inviteeId + "}", new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        int relId = obj.getAsJsonObject("data").get("relationshipId").getAsInt();
                        callback.onSuccess(relId);
                    } else {
                        callback.onError(extractError(null, json));
                    }
                } catch (Exception e) { callback.onError(buildParseError("绑定情侣", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void unbindCouple(Context context, int userId, SimpleCallback callback) {
        doRequest(context, "DELETE", "/api/couple/unbind?userId=" + userId, null, fireAndForgetCallback(callback));
    }

    public interface ChatMessageListCallback {
        void onSuccess(List<AuthApiModels.ChatMessageData> messages);
        void onError(String message);
    }

    public static void getChatMessages(Context context, int relationshipId, int limit, Long before, ChatMessageListCallback callback) {
        String path = "/api/chat/messages?relationshipId=" + relationshipId + "&limit=" + limit;
        if (before != null) path += "&before=" + before;
        doRequest(context, "GET", path, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.ChatMessageListResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.ChatMessageListResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r.data.messages != null ? r.data.messages : new ArrayList<>());
                    else callback.onError(extractError(null, json));
                } catch (Exception e) { callback.onError(buildParseError("聊天消息", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public interface ChatInsertCallback {
        void onSuccess(long messageId);
        void onError(String message);
    }

    public static void insertChatMessage(Context context, int relationshipId, int userId, String content, String messageType, String displayTime, boolean isLiked, ChatInsertCallback callback) {
        String body = "{\"relationshipId\":" + relationshipId + ",\"userId\":" + userId +
                ",\"content\":" + GSON.toJson(content) +
                ",\"messageType\":" + GSON.toJson(messageType != null ? messageType : "text") +
                (displayTime != null ? ",\"displayTime\":" + GSON.toJson(displayTime) : "") +
                ",\"isLiked\":" + isLiked + "}";
        doRequest(context, "POST", "/api/chat/messages", body, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(normalizeJsonPayload(json), com.google.gson.JsonObject.class);
                    if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
                        long msgId = obj.getAsJsonObject("data").get("messageId").getAsLong();
                        callback.onSuccess(msgId);
                    } else {
                        callback.onError(extractError(null, json));
                    }
                } catch (Exception e) { callback.onError(buildParseError("发送消息", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void toggleChatLike(Context context, long messageId, boolean isLiked, SimpleCallback callback) {
        doRequest(context, "PUT", "/api/chat/messages/" + messageId + "/like",
                "{\"isLiked\":" + isLiked + "}", fireAndForgetCallback(callback));
    }

    public static void deleteChatMessage(Context context, long messageId, SimpleCallback callback) {
        doRequest(context, "DELETE", "/api/chat/messages/" + messageId, null, fireAndForgetCallback(callback));
    }

    public static void searchChatMessages(Context context, int relationshipId, String keyword, ChatMessageListCallback callback) {
        doRequest(context, "GET", "/api/chat/search?relationshipId=" + relationshipId + "&keyword=" + keyword, null, new RawCallback() {
            @Override public void onSuccess(String json) {
                if (callback == null) return;
                try {
                    AuthApiModels.ChatMessageListResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.ChatMessageListResponse.class);
                    if (r != null && r.ok) callback.onSuccess(r.data.messages != null ? r.data.messages : new ArrayList<>());
                    else callback.onError(extractError(null, json));
                } catch (Exception e) { callback.onError(buildParseError("搜索消息", json)); }
            }
            @Override public void onError(String m) { if (callback != null) callback.onError(m); }
        });
    }

    public static void querySharedPlans(Context context, int userId, SharedPlanListCallback callback) {
        doRequest(context, "GET", "/api/shared-plans?userId=" + userId,
                null,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback == null) return;
                        try {
                            AuthApiModels.SharedPlanListResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.SharedPlanListResponse.class);
                            if (response != null && response.ok) callback.onSuccess(response);
                            else callback.onError(extractError(response != null ? response.error : null, json));
                        } catch (Exception e) {
                            Log.e(TAG, "querySharedPlans 响应解析失败: " + json, e);
                            callback.onError(buildParseError("共同计划查询", json));
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void createSharedPlan(Context context, AuthApiModels.CreateSharedPlanRequest request, SharedPlanMutationCallback callback) {
        doRequest(context, "POST", "/api/shared-plans",
                GSON.toJson(request),
                sharedPlanMutationCallback("创建共同计划", callback));
    }

    public static void adjustSharedPlan(Context context, int planId, AuthApiModels.AdjustSharedPlanRequest request, SharedPlanMutationCallback callback) {
        doRequest(context, "POST", "/api/shared-plans/" + planId + "/adjust",
                GSON.toJson(request),
                sharedPlanMutationCallback("调整共同计划", callback));
    }
    public static void deleteSharedPlan(Context context, int planId, int userId, SharedPlanMutationCallback callback) {
        doRequest(context, "DELETE", "/api/shared-plans/" + planId + "?userId=" + userId,
                null,
                sharedPlanMutationCallback("删除共同计划", callback));
    }

    public static void queryTodos(Context context, int userId, TodoListCallback callback) {
        doRequest(context, "GET", "/api/todos?userId=" + userId,
                null,
                new RawCallback() {
                    @Override
                    public void onSuccess(String json) {
                        if (callback == null) return;
                        try {
                            AuthApiModels.TodoListResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.TodoListResponse.class);
                            if (response != null && response.ok) callback.onSuccess(response);
                            else callback.onError(extractError(response != null ? response.error : null, json));
                        } catch (Exception e) {
                            Log.e(TAG, "queryTodos 响应解析失败: " + json, e);
                            callback.onError(buildParseError("代办查询", json));
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    public static void createTodo(Context context, AuthApiModels.CreateTodoRequest request, SimpleCallback callback) {
        doRequest(context, "POST", "/api/todos",
                GSON.toJson(request),
                fireAndForgetCallback(callback));
    }

    public static void updateTodo(Context context, int todoId, AuthApiModels.UpdateTodoRequest request, SimpleCallback callback) {
        doRequest(context, "PUT", "/api/todos/" + todoId,
                GSON.toJson(request),
                fireAndForgetCallback(callback));
    }

    public static void deleteTodo(Context context, int todoId, int userId, SimpleCallback callback) {
        doRequest(context, "DELETE", "/api/todos/" + todoId + "?userId=" + userId,
                null,
                fireAndForgetCallback(callback));
    }

    public static void duplicateTodo(Context context, int todoId, int userId, SimpleCallback callback) {
        doRequest(context, "POST", "/api/todos/" + todoId + "/duplicate",
                GSON.toJson(java.util.Collections.singletonMap("userId", userId)),
                fireAndForgetCallback(callback));
    }

    public static void remindPartner(Context context, int todoId, int userId, SimpleCallback callback) {
        doRequest(context, "POST", "/api/todos/" + todoId + "/remind",
                GSON.toJson(java.util.Collections.singletonMap("userId", userId)),
                fireAndForgetCallback(callback));
    }

    public static void updateAvatar(Context context, int userId, String avatarUrl, SimpleCallback callback) {
        String body = "{\"userId\":" + userId + ",\"avatarUrl\":" + GSON.toJson(avatarUrl) + "}";
        doRequest(context, "PUT", "/api/auth/avatar", body, fireAndForgetCallback(callback));
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

    private static RawCallback fireAndForgetCallback(final SimpleCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) callback.onSuccess();
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        };
    }

    private static RawCallback beadInventoryListCallback(String operation, BeadInventoryListCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BeadInventoryListResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadInventoryListResponse.class);
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

    private static RawCallback beadSettingsCallback(String operation, BeadSettingsCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BeadSettingsResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadSettingsResponse.class);
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

    private static RawCallback beadBlueprintListCallback(String operation, BeadBlueprintListCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BeadBlueprintListResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadBlueprintListResponse.class);
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

    private static RawCallback beadBlueprintDetailCallback(String operation, BeadBlueprintDetailCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BeadBlueprintResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadBlueprintResponse.class);
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

    private static RawCallback beadBlueprintCreateCallback(String operation, BeadBlueprintCreateCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BeadBlueprintCreateResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BeadBlueprintCreateResponse.class);
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

    private static RawCallback buildBeadBlueprintCallback(String operation, BuildBeadBlueprintCallback callback) {
        return new RawCallback() {
            @Override
            public void onSuccess(String json) {
                if (callback != null) {
                    try {
                        AuthApiModels.BuildBeadBlueprintResponse response = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.BuildBeadBlueprintResponse.class);
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

    private static RawCallback beadMutationCallback(String operation, BeadMutationCallback callback) {
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

    private static RawCallback simpleMutationCallback(String operation, RecipeMutationCallback callback) {
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

    private static RawCallback sharedPlanMutationCallback(String operation, SharedPlanMutationCallback callback) {
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

    private static RawCallback simpleMutationCallback(String operation, RestaurantMutationCallback callback) {
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

    private static String normalizeErrorMessage(String rawBody) {
        try {
            AuthApiModels.SimpleResponse response = GSON.fromJson(normalizeJsonPayload(rawBody), AuthApiModels.SimpleResponse.class);
            return extractError(response != null ? response.error : null, rawBody);
        } catch (Exception ignored) {
            return extractError(null, rawBody);
        }
    }

    private static void doRequest(Context context, String method, String path, String bodyJson, RawCallback callback) {
        doRequest(context, method, path, bodyJson, READ_TIMEOUT_MS, callback);
    }

    private static void doRequest(Context context, String method, String path, String bodyJson, int readTimeout, RawCallback callback) {
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
                    connection.setReadTimeout(readTimeout);

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
                    callback.onError(normalizeErrorMessage(result.text));
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
