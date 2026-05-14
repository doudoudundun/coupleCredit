package com.example.couplecredit.utils;

import android.content.Context;
import android.text.TextUtils;

import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;

import java.util.List;

public class BeadUtils {

    public interface BeadInventoryLoadCallback {
        void onSuccess(AuthApiModels.BeadInventoryListResponse response);
        void onError(String error);
    }

    public interface BeadSettingsLoadCallback {
        void onSuccess(AuthApiModels.BeadSettingsResponse response);
        void onError(String error);
    }

    public interface BeadBlueprintListLoadCallback {
        void onSuccess(AuthApiModels.BeadBlueprintListResponse response);
        void onError(String error);
    }

    public interface BeadBlueprintDetailLoadCallback {
        void onSuccess(AuthApiModels.BeadBlueprintResponse response);
        void onError(String error);
    }

    public interface BeadBlueprintCreateCallback {
        void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response);
        void onError(String error);
    }

    public interface BuildBeadBlueprintCallback {
        void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response);
        void onError(String error);
    }

    public interface BeadMutationCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface BeadRecognizeColorsCallback {
        void onSuccess(AuthApiModels.BeadRecognizeColorsResponse response);
        void onError(String error);
    }

    public static void loadInventory(Context context, BeadInventoryLoadCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.queryBeadInventory(context, userId, new AuthApiClient.BeadInventoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadInventoryListResponse response) {
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

    public static void loadSettings(Context context, BeadSettingsLoadCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.queryBeadSettings(context, userId, new AuthApiClient.BeadSettingsCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadSettingsResponse response) {
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

    public static void updateInventory(Context context, String colorCode, Integer quantity, Integer thresholdOverride,
                                       BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.UpdateBeadInventoryRequest request = new AuthApiModels.UpdateBeadInventoryRequest(
                userId,
                quantity,
                thresholdOverride
        );
        AuthApiClient.updateBeadInventory(context, normalizeColorCode(colorCode), request, wrapMutation(callback));
    }

    public static void updateSettings(Context context, int defaultThreshold, BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.BeadSettingsUpdateRequest request = new AuthApiModels.BeadSettingsUpdateRequest(userId, defaultThreshold);
        AuthApiClient.updateBeadSettings(context, request, wrapMutation(callback));
    }

    public static void consumeInventory(Context context, String colorCode, int amount, BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.consumeBeadInventory(context, normalizeColorCode(colorCode), userId, amount, wrapMutation(callback));
    }

    public static void replenishInventory(Context context, String colorCode, int amount, BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.replenishBeadInventory(context, normalizeColorCode(colorCode), userId, amount, wrapMutation(callback));
    }

    public static void loadBlueprints(Context context, BeadBlueprintListLoadCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.queryBeadBlueprints(context, userId, new AuthApiClient.BeadBlueprintListCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadBlueprintListResponse response) {
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

    public static void getBlueprintDetail(Context context, int blueprintId, BeadBlueprintDetailLoadCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.getBeadBlueprintDetail(context, blueprintId, userId, new AuthApiClient.BeadBlueprintDetailCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadBlueprintResponse response) {
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

    public static void createBlueprint(Context context, String name, String imageUrl, List<AuthApiModels.BeadBlueprintColorRequest> colors,
                                       BeadBlueprintCreateCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.CreateBeadBlueprintRequest request = new AuthApiModels.CreateBeadBlueprintRequest(
                userId,
                emptyToNull(name),
                imageUrl,
                colors
        );
        AuthApiClient.createBeadBlueprint(context, request, new AuthApiClient.BeadBlueprintCreateCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response) {
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

    public static void updateBlueprint(Context context, int blueprintId, String name, String imageUrl,
                                       List<AuthApiModels.BeadBlueprintColorRequest> colors,
                                       BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiModels.UpdateBeadBlueprintRequest request = new AuthApiModels.UpdateBeadBlueprintRequest(
                userId,
                emptyToNullableString(name),
                imageUrl,
                colors
        );
        AuthApiClient.updateBeadBlueprint(context, blueprintId, request, wrapMutation(callback));
    }

    public static void deleteBlueprint(Context context, int blueprintId, BeadMutationCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.deleteBeadBlueprint(context, blueprintId, userId, wrapMutation(callback));
    }

    public static void buildBlueprint(Context context, int blueprintId, Integer count, BuildBeadBlueprintCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onError("用户未登录");
            }
            return;
        }

        AuthApiClient.buildBeadBlueprint(context, blueprintId, userId, count, new AuthApiClient.BuildBeadBlueprintCallback() {
            @Override
            public void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response) {
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

    public static void recognizeColors(Context context, String imageUrl, BeadRecognizeColorsCallback callback) {
        AuthApiClient.recognizeBeadColors(context, imageUrl, new AuthApiClient.BeadRecognizeColorsCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadRecognizeColorsResponse response) {
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

    public interface BeadConvertCallback {
        void onSuccess(AuthApiModels.BeadConvertResponse response);
        void onError(String error);
    }

    public static void convertToBeadImage(Context context, String imageUrl, int cols, BeadConvertCallback callback) {
        convertToBeadImage(context, imageUrl, cols, null, null, null, callback);
    }

    public static void convertToBeadImage(Context context, String imageUrl, int cols, Integer rows, Integer matchThreshold, Integer smoothExtra, BeadConvertCallback callback) {
        AuthApiClient.convertToBeadImage(context, imageUrl, cols, rows, matchThreshold, smoothExtra, new AuthApiClient.BeadConvertCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadConvertResponse response) {
                if (callback != null) callback.onSuccess(response);
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    private static AuthApiClient.BeadMutationCallback wrapMutation(BeadMutationCallback callback) {
        return new AuthApiClient.BeadMutationCallback() {
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

    public static String normalizeColorCode(String colorCode) {
        if (colorCode == null) {
            return null;
        }
        String normalized = colorCode.trim().toUpperCase();
        if (normalized.matches("^[A-Z]\\d$")) {
            return normalized.substring(0, 1) + "0" + normalized.substring(1);
        }
        return normalized;
    }

    public static int parseColorSafely(String value) {
        if (value == null || value.isEmpty()) {
            return 0xFFE5E7EB;
        }
        try {
            return android.graphics.Color.parseColor(value);
        } catch (Exception ignored) {
            return 0xFFE5E7EB;
        }
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
