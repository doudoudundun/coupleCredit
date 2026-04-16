package com.example.couplecredit.utils;

import android.content.Context;
import android.text.TextUtils;

import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;

public class InventoryUtils {

    public interface InventoryLoadCallback {
        void onSuccess(AuthApiModels.InventoryListResponse response);
        void onError(String error);
    }

    public interface InventoryMutationCallback {
        void onSuccess();
        void onError(String error);
    }

    public static void loadInventory(Context context, InventoryLoadCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.queryInventory(context, userId, new AuthApiClient.InventoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.InventoryListResponse response) {
                if (callback != null) {
                    callback.onSuccess(response);
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    public static void createInventory(Context context, String name, String category, double quantity, String unit,
                                       double threshold, String imageUrl, String note, String aiImagePrompt,
                                       InventoryMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.CreateInventoryRequest request = new AuthApiModels.CreateInventoryRequest(
                userId,
                name,
                category,
                quantity,
                unit,
                threshold,
                emptyToNull(imageUrl),
                emptyToNull(note),
                emptyToNull(aiImagePrompt)
        );

        AuthApiClient.createInventory(context, request, wrapMutation(callback));
    }

    public static void updateInventory(Context context, int inventoryId, String name, String category, double quantity,
                                       String unit, double threshold, String imageUrl, String note, String aiImagePrompt,
                                       InventoryMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.UpdateInventoryRequest request = new AuthApiModels.UpdateInventoryRequest(
                userId,
                name,
                category,
                quantity,
                unit,
                threshold,
                emptyToNullableString(imageUrl),
                emptyToNullableString(note),
                emptyToNullableString(aiImagePrompt)
        );

        AuthApiClient.updateInventory(context, inventoryId, request, wrapMutation(callback));
    }

    public static void consumeInventory(Context context, int inventoryId, double amount, InventoryMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.consumeInventory(context, inventoryId, userId, amount, wrapMutation(callback));
    }

    public static void replenishInventory(Context context, int inventoryId, double amount, InventoryMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.replenishInventory(context, inventoryId, userId, amount, wrapMutation(callback));
    }

    public static void deleteInventory(Context context, int inventoryId, InventoryMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.deleteInventory(context, inventoryId, userId, wrapMutation(callback));
    }

    private static AuthApiClient.InventoryMutationCallback wrapMutation(InventoryMutationCallback callback) {
        return new AuthApiClient.InventoryMutationCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError(message);
                }
            }
        };
    }

    private static String emptyToNull(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.trim();
    }

    private static String emptyToNullableString(String value) {
        if (value == null) {
            return null;
        }
        return value.trim();
    }
}
