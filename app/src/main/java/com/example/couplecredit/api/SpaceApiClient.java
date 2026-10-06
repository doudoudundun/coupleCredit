package com.example.couplecredit.api;

import android.content.Context;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * v0.2 共同空间 API 客户端（共同目标 / 情境邀请 / 解绑 / 共同小记 / 空间背景）。
 * 端点与小程序端 api/goals.js、api/diary.js 一一对应；请求通道复用
 * AuthApiClient.doRequest（统一注入 JWT，401 自动续期）。
 * 当前为空壳配套：页面骨架尚未接线，方法供后续迭代直接调用。
 */
public class SpaceApiClient {
    private static final String TAG = "SpaceApiClient";

    public interface JsonCallback {
        void onSuccess(JsonObject data);
        void onError(String message);
    }

    // ===== 共同目标 =====

    /** 当前空间目标：GET /api/spaces/current/goal */
    public static void getCurrentGoal(Context context, JsonCallback callback) {
        AuthApiClient.doRequest(context, "GET", "/api/spaces/current/goal", null, wrap(callback));
    }

    /** 创建目标：POST /api/goals { title, targetAmountFen, targetDate?, idempotencyKey } */
    public static void createGoal(Context context, JsonObject payload, JsonCallback callback) {
        AuthApiClient.doRequest(context, "POST", "/api/goals", payload.toString(), wrap(callback));
    }

    /** 目标流水：POST /api/goals/{goalId}/entries { type, amountFen, note?, idempotencyKey } */
    public static void addGoalEntry(Context context, String goalId, JsonObject payload, JsonCallback callback) {
        AuthApiClient.doRequest(context, "POST", "/api/goals/" + goalId + "/entries", payload.toString(), wrap(callback));
    }

    /** 流水冲正：POST /api/entries/{entryId}/reverse */
    public static void reverseEntry(Context context, String entryId, JsonCallback callback) {
        AuthApiClient.doRequest(context, "POST", "/api/entries/" + entryId + "/reverse", "{}", wrap(callback));
    }

    // ===== 情境邀请 =====

    /** 发起邀请：POST /api/invitations { goalId } */
    public static void createInvitation(Context context, String goalId, JsonCallback callback) {
        JsonObject payload = new JsonObject();
        payload.addProperty("goalId", goalId);
        AuthApiClient.doRequest(context, "POST", "/api/invitations", payload.toString(), wrap(callback));
    }

    /** 邀请预览（允许未登录）：GET /api/invitations/preview?token= */
    public static void previewInvitation(Context context, String token, JsonCallback callback) {
        AuthApiClient.doRequest(context, "GET", "/api/invitations/preview?token=" + ApiEncoding.query(token), null, wrap(callback));
    }

    /** 接受邀请：POST /api/invitations/accept { token, idempotencyKey } */
    public static void acceptInvitation(Context context, String token, String idempotencyKey, JsonCallback callback) {
        JsonObject payload = new JsonObject();
        payload.addProperty("token", token);
        payload.addProperty("idempotencyKey", idempotencyKey);
        AuthApiClient.doRequest(context, "POST", "/api/invitations/accept", payload.toString(), wrap(callback));
    }

    // ===== 解除关系 =====

    /** 解绑（目标冻结+小记私有归档）：POST /api/relationships/{id}/unbind */
    public static void unbindRelationship(Context context, String relationshipId, String idempotencyKey, JsonCallback callback) {
        JsonObject payload = new JsonObject();
        payload.addProperty("idempotencyKey", idempotencyKey);
        AuthApiClient.doRequest(context, "POST", "/api/relationships/" + relationshipId + "/unbind", payload.toString(), wrap(callback));
    }

    // ===== 共同小记 =====

    /** 小记时间线：GET /api/diary（query 可空，如 "month=2026-10"） */
    public static void listDiary(Context context, String query, JsonCallback callback) {
        String path = "/api/diary" + (query == null || query.isEmpty() ? "" : "?" + query);
        AuthApiClient.doRequest(context, "GET", path, null, wrap(callback));
    }

    /** 发小记：POST /api/diary { body, occurredOn, moodCode?, mediaIds?, idempotencyKey } */
    public static void createDiary(Context context, JsonObject payload, JsonCallback callback) {
        AuthApiClient.doRequest(context, "POST", "/api/diary", payload.toString(), wrap(callback));
    }

    /** 空间背景：GET /api/spaces/current/diary-theme */
    public static void getDiaryTheme(Context context, JsonCallback callback) {
        AuthApiClient.doRequest(context, "GET", "/api/spaces/current/diary-theme", null, wrap(callback));
    }

    /** 保存背景：PATCH /api/spaces/current/diary-theme { kind, templateId?, overlay?, expectedVersion } */
    public static void saveDiaryTheme(Context context, JsonObject payload, JsonCallback callback) {
        AuthApiClient.doRequest(context, "PATCH", "/api/spaces/current/diary-theme", payload.toString(), wrap(callback));
    }

    // ===== 内部 =====

    /** 拆 { ok, data } 信封；空 data（如解绑返回）兜底为空对象 */
    private static AuthApiClient.RawCallback wrap(JsonCallback callback) {
        return new AuthApiClient.RawCallback() {
            @Override
            public void onSuccess(String json) {
                try {
                    JsonObject body = JsonParser.parseString(json).getAsJsonObject();
                    if (body.has("data") && body.get("data").isJsonObject()) {
                        callback.onSuccess(body.getAsJsonObject("data"));
                    } else {
                        callback.onSuccess(new JsonObject());
                    }
                } catch (Exception e) {
                    callback.onError("响应解析失败");
                }
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        };
    }
}
