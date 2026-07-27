package com.example.couplecredit.api;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.List;

public class AuthApiModels {
    public static class AuthSuccessData {
        @SerializedName(value = "userId", alternate = {"id", "user_id"})
        public int userId;
        @SerializedName(value = "username", alternate = {"userName", "name"})
        public String username;
        public String email;
        // JWT 鉴权 token（登录/刷新/微信登录成功时返回）
        public String accessToken;
        public String refreshToken;
        // 微信登录未绑定时返回 bound=false + openid
        public Boolean bound;
        public String openid;
    }

    public static class BillData {
        public long billId;
        public Integer relationshipId;
        public Integer sharedPlanId;
        public String sharedPlanName;
        public int owner;
        public int userId;
        public String title;
        public String type;
        public double amount;
        public String date;
        public String time;
        public int incomeType;
        public int isHelp;
    }

    public static class InventoryItemData {
        public int inventoryId;
        public int userId;
        public Integer relationshipId;
        public String name;
        public String category;
        public String imageUrl;
        public double quantity;
        public String unit;
        public double threshold;
        public String expirationMode;
        public String expirationDate;
        public String productionDate;
        public Integer shelfLifeDays;
        public String createdAt;
        public String updatedAt;
        public String lastConsumedAt;
        public String note;
        public String aiImagePrompt;
        public boolean isLowStock;
        public boolean isExpiring;
        public boolean isExpired;
    }

    public static class BillsQueryData {
        public List<BillData> bills;
        public Integer relationshipId;
        public int year;
        public int month;
    }

    public static class InventoryListData {
        public List<InventoryItemData> items;
        public Integer relationshipId;
        public Integer count;
    }

    public static class BeadInventoryItemData {
        public String colorCode;
        public String hexColor;
        public int quantity;
        public Integer thresholdOverride;
        public int defaultThreshold;
        public int totalConsumed;
        public String colorGroup;
        public boolean isTransparent;
        public boolean isLowStock;
    }

    public static class BeadSummaryData {
        public int totalColors;
        public int lowStockCount;
        public int totalConsumptionReference;
    }

    public static class BeadInventoryListData {
        public List<BeadInventoryItemData> items;
        public BeadSummaryData summary;
        public Integer relationshipId;
    }

    public static class BeadSettingsData {
        public int defaultThreshold;
    }

    public static class BeadBlueprintColorData {
        public String colorCode;
        public String hexColor;
        public String colorGroup;
        public boolean isTransparent;
        @SerializedName(value = "quantityPerBuild", alternate = {"quantity"})
        public int quantityPerBuild;
        public Integer totalConsumed;
    }

    public static class BeadBlueprintItemData {
        public int blueprintId;
        public int userId;
        public Integer relationshipId;
        public boolean isPartner;
        public String name;
        public String imageUrl;
        public int buildCount;
        public Integer colorCount;
        public Integer totalBeadsPerBuild;
        public Integer totalConsumed;
        public String createdAt;
        public String updatedAt;
        public List<BeadBlueprintColorData> colors;
    }

    public static class BeadBlueprintListData {
        public List<BeadBlueprintItemData> items;
    }

    public static class BeadBlueprintCreateData {
        public int blueprintId;
    }

    public static class ErrorBody {
        public String code;
        public String message;
    }

    public static class AuthResponse {
        public boolean ok;
        public String message;
        @SerializedName(value = "data", alternate = {"user"})
        public AuthSuccessData data;
        public ErrorBody error;
    }

    public static class BillResponse {
        public boolean ok;
        public String message;
        public BillData data;
        public ErrorBody error;
    }

    public static class BillsQueryResponse {
        public boolean ok;
        public String message;
        public BillsQueryData data;
        public ErrorBody error;
    }

    public static class InventoryListResponse {
        public boolean ok;
        public String message;
        public InventoryListData data;
        public ErrorBody error;
    }

    public static class BeadInventoryListResponse {
        public boolean ok;
        public String message;
        public BeadInventoryListData data;
        public ErrorBody error;
    }

    public static class BeadSettingsResponse {
        public boolean ok;
        public String message;
        public BeadSettingsData data;
        public ErrorBody error;
    }

    public static class BeadBlueprintListResponse {
        public boolean ok;
        public String message;
        public BeadBlueprintListData data;
        public ErrorBody error;
    }

    public static class BeadBlueprintResponse {
        public boolean ok;
        public String message;
        public BeadBlueprintItemData data;
        public ErrorBody error;
    }

    public static class BeadBlueprintCreateResponse {
        public boolean ok;
        public String message;
        public BeadBlueprintCreateData data;
        public ErrorBody error;
    }

    public static class SimpleResponse {
        public boolean ok;
        public String message;
        public ErrorBody error;
    }

    public static class UpdateBillRequest {
        public final int userId;
        public final String title;
        public final String type;
        public final double amount;
        public final String date;
        public final String time;
        public final Integer incomeType;

        public UpdateBillRequest(int userId, String title, String type, double amount, String date, String time, Integer incomeType) {
            this.userId = userId;
            this.title = title;
            this.type = type;
            this.amount = amount;
            this.date = date;
            this.time = time;
            this.incomeType = incomeType;
        }
    }

    public static class CreateInventoryRequest {
        public final int userId;
        public final String name;
        public final String category;
        public final double quantity;
        public final String unit;
        public final double threshold;
        public final String expirationMode;
        public final String expirationDate;
        public final String productionDate;
        public final Integer shelfLifeDays;
        public final String imageUrl;
        public final String note;
        public final String aiImagePrompt;

        public CreateInventoryRequest(int userId, String name, String category, double quantity, String unit, double threshold,
                                      String expirationMode, String expirationDate, String productionDate, Integer shelfLifeDays,
                                      String imageUrl, String note, String aiImagePrompt) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.unit = unit;
            this.threshold = threshold;
            this.expirationMode = expirationMode;
            this.expirationDate = expirationDate;
            this.productionDate = productionDate;
            this.shelfLifeDays = shelfLifeDays;
            this.imageUrl = imageUrl;
            this.note = note;
            this.aiImagePrompt = aiImagePrompt;
        }
    }

    public static class UpdateInventoryRequest {
        public final int userId;
        public final String name;
        public final String category;
        public final Double quantity;
        public final String unit;
        public final Double threshold;
        public final String expirationMode;
        public final String expirationDate;
        public final String productionDate;
        public final Integer shelfLifeDays;
        public final String imageUrl;
        public final String note;
        public final String aiImagePrompt;

        public UpdateInventoryRequest(int userId, String name, String category, Double quantity, String unit, Double threshold,
                                      String expirationMode, String expirationDate, String productionDate, Integer shelfLifeDays,
                                      String imageUrl, String note, String aiImagePrompt) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.unit = unit;
            this.threshold = threshold;
            this.expirationMode = expirationMode;
            this.expirationDate = expirationDate;
            this.productionDate = productionDate;
            this.shelfLifeDays = shelfLifeDays;
            this.imageUrl = imageUrl;
            this.note = note;
            this.aiImagePrompt = aiImagePrompt;
        }
    }

    public static class InventoryAmountRequest {
        public final int userId;
        public final double consumeAmount;
        public final double addAmount;

        public InventoryAmountRequest(int userId, double consumeAmount, double addAmount) {
            this.userId = userId;
            this.consumeAmount = consumeAmount;
            this.addAmount = addAmount;
        }
    }

    public static class UpdateBeadInventoryRequest {
        public final int userId;
        public final Integer quantity;
        public final Integer thresholdOverride;

        public UpdateBeadInventoryRequest(int userId, Integer quantity, Integer thresholdOverride) {
            this.userId = userId;
            this.quantity = quantity;
            this.thresholdOverride = thresholdOverride;
        }
    }

    @Deprecated
    public static class BeadInventoryUpdateRequest extends UpdateBeadInventoryRequest {
        public BeadInventoryUpdateRequest(int userId, Integer quantity, Integer thresholdOverride) {
            super(userId, quantity, thresholdOverride);
        }
    }

    public static class BeadSettingsUpdateRequest {
        public final int userId;
        public final int defaultThreshold;

        public BeadSettingsUpdateRequest(int userId, int defaultThreshold) {
            this.userId = userId;
            this.defaultThreshold = defaultThreshold;
        }
    }

    public static class BeadInventoryAmountRequest {
        public final int userId;
        public final Integer consumeAmount;
        public final Integer addAmount;

        public BeadInventoryAmountRequest(int userId, Integer consumeAmount, Integer addAmount) {
            this.userId = userId;
            this.consumeAmount = consumeAmount;
            this.addAmount = addAmount;
        }
    }

    public static class BeadBlueprintColorRequest {
        public final String colorCode;
        @SerializedName(value = "quantityPerBuild", alternate = {"quantity"})
        public final int quantityPerBuild;

        public BeadBlueprintColorRequest(String colorCode, int quantityPerBuild) {
            this.colorCode = colorCode;
            this.quantityPerBuild = quantityPerBuild;
        }
    }

    public static class CreateBeadBlueprintRequest {
        public final int userId;
        public final String name;
        public final String imageUrl;
        public final List<BeadBlueprintColorRequest> colors;

        public CreateBeadBlueprintRequest(int userId, String name, String imageUrl, List<BeadBlueprintColorRequest> colors) {
            this.userId = userId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.colors = colors;
        }
    }

    public static class UpdateBeadBlueprintRequest {
        public final int userId;
        public final String name;
        public final String imageUrl;
        public final List<BeadBlueprintColorRequest> colors;

        public UpdateBeadBlueprintRequest(int userId, String name, String imageUrl, List<BeadBlueprintColorRequest> colors) {
            this.userId = userId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.colors = colors;
        }
    }

    public static class BeadRecognizeColorsRequest {
        public final String imageUrl;

        public BeadRecognizeColorsRequest(String imageUrl) {
            this.imageUrl = imageUrl;
        }
    }

    public static class BeadRecognizedColorData {
        public String colorCode;
        public int quantityPerBuild;
    }

    public static class BeadRecognizeColorsData {
        public List<BeadRecognizedColorData> colors;
        public String rawText;
        public boolean recognized;
    }

    public static class BeadRecognizeColorsResponse {
        public boolean ok;
        public String message;
        public BeadRecognizeColorsData data;
        public ErrorBody error;
    }

    public static class BeadConvertRequest {
        public final String imageUrl;
        public final Integer cols;
        public final Integer rows;
        public final Integer cellSize;
        public final Boolean renderImage;
        public final Integer matchThreshold;
        public final Integer smoothExtra;

        public BeadConvertRequest(String imageUrl, Integer cols, Integer rows, Integer cellSize, Boolean renderImage) {
            this(imageUrl, cols, rows, cellSize, renderImage, null, null);
        }

        public BeadConvertRequest(String imageUrl, Integer cols, Integer rows, Integer cellSize, Boolean renderImage, Integer matchThreshold, Integer smoothExtra) {
            this.imageUrl = imageUrl;
            this.cols = cols;
            this.rows = rows;
            this.cellSize = cellSize;
            this.renderImage = renderImage;
            this.matchThreshold = matchThreshold;
            this.smoothExtra = smoothExtra;
        }
    }

    public static class BeadConvertColorData {
        public String colorCode;
        public int quantity;
        public String hexColor;
    }

    public static class BeadConvertData {
        public String imageDataUrl;
        public List<BeadConvertColorData> colors;
        public String colorSummaryText;
        public List<List<String>> gridData;
    }

    public static class BeadConvertResponse {
        public boolean ok;
        public BeadConvertData data;
        public ErrorBody error;
    }

    public static class BuildBeadBlueprintRequest {
        public final int userId;
        public final Integer count;

        public BuildBeadBlueprintRequest(int userId, Integer count) {
            this.userId = userId;
            this.count = count;
        }
    }

    @JsonAdapter(BuildBeadBlueprintDataAdapter.class)
    public static class BuildBeadBlueprintData {
        public int buildCount;
        public int previousBuildCount;
        public int addedCount;
        public int currentBuildCount;
        public List<ConsumedColorData> consumedColors;
    }

    public static class ConsumedColorData {
        public String colorCode;
        public String hexColor;
        public int quantity;
    }

    public static class BuildBeadBlueprintDataAdapter implements JsonDeserializer<BuildBeadBlueprintData> {
        @Override
        public BuildBeadBlueprintData deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            BuildBeadBlueprintData data = new BuildBeadBlueprintData();
            if (json == null || !json.isJsonObject()) {
                return data;
            }

            JsonObject object = json.getAsJsonObject();
            boolean hasBuildCount = object.has("buildCount") && !object.get("buildCount").isJsonNull();
            boolean hasCurrentBuildCount = object.has("currentBuildCount") && !object.get("currentBuildCount").isJsonNull();

            if (hasBuildCount) {
                data.buildCount = object.get("buildCount").getAsInt();
            }
            if (object.has("previousBuildCount") && !object.get("previousBuildCount").isJsonNull()) {
                data.previousBuildCount = object.get("previousBuildCount").getAsInt();
            }
            if (object.has("addedCount") && !object.get("addedCount").isJsonNull()) {
                data.addedCount = object.get("addedCount").getAsInt();
            }
            if (hasCurrentBuildCount) {
                data.currentBuildCount = object.get("currentBuildCount").getAsInt();
            }

            if (!hasBuildCount) {
                data.buildCount = data.currentBuildCount;
            }
            if (!hasCurrentBuildCount) {
                data.currentBuildCount = data.buildCount;
            }
            if (object.has("consumedColors") && object.get("consumedColors").isJsonArray()) {
                data.consumedColors = new java.util.ArrayList<>();
                for (JsonElement elem : object.getAsJsonArray("consumedColors")) {
                    JsonObject obj = elem.getAsJsonObject();
                    ConsumedColorData c = new ConsumedColorData();
                    c.colorCode = obj.has("colorCode") ? obj.get("colorCode").getAsString() : "";
                    c.hexColor = obj.has("hexColor") ? obj.get("hexColor").getAsString() : "#DDDDDD";
                    c.quantity = obj.has("quantity") ? obj.get("quantity").getAsInt() : 0;
                    data.consumedColors.add(c);
                }
            }
            return data;
        }
    }

    public static class BuildBeadBlueprintResponse {
        public boolean ok;
        public String message;
        public BuildBeadBlueprintData data;
        public ErrorBody error;
    }

    public static class BatchDeductRequest {
        public final int userId;
        public final java.util.List<BatchDeductItem> items;

        public BatchDeductRequest(int userId, java.util.List<BatchDeductItem> items) {
            this.userId = userId;
            this.items = items;
        }
    }

    public static class BatchDeductItem {
        public final String colorCode;
        public final int quantity;
        public BatchDeductItem(String colorCode, int quantity) {
            this.colorCode = colorCode;
            this.quantity = quantity;
        }
    }

    public static class BatchDeductResponse {
        public boolean ok;
        public String message;
        public ErrorBody error;
    }

    public static class RegisterRequest {
        public final String username;
        public final String email;
        public final String password;
        public final String inviteCode;

        public RegisterRequest(String username, String email, String password, String inviteCode) {
            this.username = username;
            this.email = email;
            this.password = password;
            this.inviteCode = inviteCode;
        }
    }

    public static class LoginRequest {
        public final String username;
        public final String password;

        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    public static class CreateBillRequest {
        public final int userId;
        public final String billOwner;
        public final Integer sharedPlanId;
        public final String title;
        public final String type;
        public final double amount;
        public final String date;
        public final String time;
        public final int incomeType;

        public CreateBillRequest(int userId, String billOwner, Integer sharedPlanId, String title, String type, double amount, String date, String time, int incomeType) {
            this.userId = userId;
            this.billOwner = billOwner;
            this.sharedPlanId = sharedPlanId;
            this.title = title;
            this.type = type;
            this.amount = amount;
            this.date = date;
            this.time = time;
            this.incomeType = incomeType;
        }
    }

    // --- Recipe models ---

    public static class RecipeListResponse {
        public boolean ok;
        public RecipeListData data;
        public ErrorBody error;
    }

    public static class RecipeListData {
        public List<RecipeItemData> items;
        public Integer relationshipId;
    }

    public static class RecipeItemData {
        public int recipeId;
        public int userId;
        public Integer categoryId;
        public String title;
        public String description;
        public String imageUrl;
        public String steps;
        public Double totalCalories;
        public String calorieSource;
        public int ingredientCount;
        public String createdAt;
        public String updatedAt;
    }

    public static class RecipeDetailResponse {
        public boolean ok;
        public RecipeDetailData data;
        public ErrorBody error;
    }

    public static class RecipeDetailData {
        public int recipeId;
        public int userId;
        public String title;
        public String description;
        public String imageUrl;
        public String steps;
        public Double totalCalories;
        public String calorieSource;
        public String createdAt;
        public String updatedAt;
        public List<IngredientData> ingredients;
    }

    public static class IngredientData {
        public int id;
        public Integer inventoryId;
        public String ingredientName;
        public double quantity;
        public String unit;
    }

    public static class CreateRecipeRequest {
        public int userId;
        public String title;
        public String description;
        public String imageUrl;
        public String steps;
        public Integer categoryId;
        public Double totalCalories;
        public java.util.List<IngredientData> ingredients;

        public CreateRecipeRequest(int userId, String title, String description, String imageUrl, String steps, Integer categoryId, Double totalCalories, java.util.List<IngredientData> ingredients) {
            this.userId = userId;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.steps = steps;
            this.categoryId = categoryId;
            this.totalCalories = totalCalories;
            this.ingredients = ingredients;
        }
    }

    public static class UpdateRecipeRequest {
        public int userId;
        public String title;
        public String description;
        public String imageUrl;
        public String steps;
        public Integer categoryId;
        public Double totalCalories;
        public java.util.List<IngredientData> ingredients;

        public UpdateRecipeRequest(int userId, String title, String description, String imageUrl, String steps, Integer categoryId, Double totalCalories, java.util.List<IngredientData> ingredients) {
            this.userId = userId;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.steps = steps;
            this.categoryId = categoryId;
            this.totalCalories = totalCalories;
            this.ingredients = ingredients;
        }
    }

    public static class CookRequest {
        public int userId;
        public CookRequest(int userId) { this.userId = userId; }
    }

    public static class CookResponse {
        public boolean ok;
        public CookResultData data;
    }

    public static class CookResultData {
        public List<CookResultItem> results;
        public List<String> warnings;
    }

    public static class CookResultItem {
        public String name;
        public double consumed;
        public String unit;
        public boolean hadEnough;
    }

    // Recipe Categories
    public static class RecipeCategoryData {
        public int categoryId;
        public int relationshipId;
        public String name;
        public int sortOrder;
        public String createdAt;
    }

    public static class RecipeCategoryListResponse {
        public boolean ok;
        public RecipeCategoryListData data;
        public ErrorBody error;
    }

    public static class RecipeCategoryListData {
        public List<RecipeCategoryData> items;
    }

    // Recipe Recommendation
    public static class RecipeRecommendResponse {
        public boolean ok;
        public RecipeRecommendData data;
        public ErrorBody error;
    }

    public static class RecipeRecommendData {
        public List<RecommendRecipeItem> recipes;
        public List<String> availableIngredients;
    }

    public static class RecommendRecipeItem {
        public int recipeId;
        public String title;
        public String description;
        public String imageUrl;
        public Integer categoryId;
        public Double totalCalories;
        public String calorieSource;
        public MatchInfo matchInfo;
    }

    public static class MatchInfo {
        public int total;
        public int matched;
        public List<String> matchedIngredients;
        public List<String> missingIngredients;
    }

    // User profile
    public static class UserProfileResponse {
        public boolean ok;
        public UserProfileData data;
        public ErrorBody error;
    }

    public static class UserProfileData {
        public int userId;
        public String username;
        public String email;
        public String nickname;
        public String avatarUrl;
    }

    // Chat messages
    public static class ChatMessageListResponse {
        public boolean ok;
        public ChatMessageListData data;
    }

    public static class ChatMessageListData {
        public List<ChatMessageData> messages;
    }

    public static class ChatMessageData {
        public long id;
        @SerializedName("relationship_id")
        public int relationshipId;
        @SerializedName("user_id")
        public int userId;
        public String content;
        @SerializedName("message_type")
        public String messageType;
        @SerializedName("display_time")
        public String displayTime;
        @SerializedName("created_at")
        public long createdAt;
        @SerializedName("is_liked")
        public boolean isLiked;
    }

    // Shared plans
    public static class SharedPlanData {
        public int planId;
        public Integer relationshipId;
        public int createdBy;
        public String name;
        public double initialAmount;
        public double currentBalance;
        public String visibility;
        public String createdAt;
        public String updatedAt;
    }

    public static class SharedPlanListData {
        public List<SharedPlanData> items;
        public Integer relationshipId;
    }

    public static class SharedPlanListResponse {
        public boolean ok;
        public SharedPlanListData data;
        public ErrorBody error;
    }

    public static class CreateSharedPlanRequest {
        public final int userId;
        public final String name;
        public final double initialAmount;
        public final String visibility;

        public CreateSharedPlanRequest(int userId, String name, double initialAmount, String visibility) {
            this.userId = userId;
            this.name = name;
            this.initialAmount = initialAmount;
            this.visibility = visibility;
        }
    }

    public static class AdjustSharedPlanRequest {
        public final int userId;
        public final double amount;
        public final String direction;

        public AdjustSharedPlanRequest(int userId, double amount, String direction) {
            this.userId = userId;
            this.amount = amount;
            this.direction = direction;
        }
    }

    public static class TodoItemData {
        public int todoId;
        public int userId;
        public Integer relationshipId;
        public String title;
        public String content;
        public String priority;
        public String fuzzyDateText;
        public String imageUrl;
        public String status;
        public boolean isRepeatable;
        public Long seriesId;
        public int completedCount;
        public String createdAt;
        public String updatedAt;
    }

    public static class TodoListData {
        public List<TodoItemData> items;
        public Integer relationshipId;
    }

    public static class TodoListResponse {
        public boolean ok;
        public TodoListData data;
        public ErrorBody error;
    }

    public static class CreateTodoRequest {
        public final int userId;
        public final String title;
        public final String content;
        public final String priority;
        public final String fuzzyDateText;
        public final String imageUrl;
        public final String status;
        public final boolean isRepeatable;
        public final Long seriesId;
        public final Integer completedCount;

        public CreateTodoRequest(int userId, String title, String content, String priority, String fuzzyDateText, String imageUrl, String status,
                                 boolean isRepeatable, Long seriesId, Integer completedCount) {
            this.userId = userId;
            this.title = title;
            this.content = content;
            this.priority = priority;
            this.fuzzyDateText = fuzzyDateText;
            this.imageUrl = imageUrl;
            this.status = status;
            this.isRepeatable = isRepeatable;
            this.seriesId = seriesId;
            this.completedCount = completedCount;
        }
    }

    public static class UpdateTodoRequest {
        public final int userId;
        public final String title;
        public final String content;
        public final String priority;
        public final String fuzzyDateText;
        public final String imageUrl;
        public final String status;
        public final boolean isRepeatable;
        public final Long seriesId;
        public final Integer completedCount;

        public UpdateTodoRequest(int userId, String title, String content, String priority, String fuzzyDateText, String imageUrl, String status,
                                 boolean isRepeatable, Long seriesId, Integer completedCount) {
            this.userId = userId;
            this.title = title;
            this.content = content;
            this.priority = priority;
            this.fuzzyDateText = fuzzyDateText;
            this.imageUrl = imageUrl;
            this.status = status;
            this.isRepeatable = isRepeatable;
            this.seriesId = seriesId;
            this.completedCount = completedCount;
        }
    }

    // Notifications
    public static class NotificationItem {
        public int notificationId;
        public String type;
        public String title;
        public String body;
        public Integer relatedId;
        public boolean isRead;
        public String createdAt;
    }

    public static class NotificationListData {
        public List<NotificationItem> items;
    }

    public static class NotificationListResponse {
        public boolean ok;
        public NotificationListData data;
        public ErrorBody error;
    }

    // Restaurants (Eat Out)
    public static class RestaurantListResponse {
        public boolean ok;
        public RestaurantListData data;
        public ErrorBody error;
    }

    public static class RestaurantListData {
        public List<RestaurantItemData> items;
        public Integer relationshipId;
    }

    public static class RestaurantItemData {
        public int restaurantId;
        public int userId;
        public Integer relationshipId;
        public String name;
        public String category;
        public String imageUrl;
        public String routeImageUrl;
        public Double avgCost;
        public Double defaultCalories;
        public Double distance;
        public String address;
        public String note;
        public String createdAt;
        public String updatedAt;
    }

    public static class RestaurantRequest {
        public int userId;
        public String name;
        public String category;
        public String imageUrl;
        public String routeImageUrl;
        public Double avgCost;
        public Double defaultCalories;
        public Double distance;
        public String address;
        public String note;

        public RestaurantRequest(int userId, String name, String category, String imageUrl, String routeImageUrl, Double avgCost, Double defaultCalories, Double distance, String address, String note) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.imageUrl = imageUrl;
            this.routeImageUrl = routeImageUrl;
            this.avgCost = avgCost;
            this.defaultCalories = defaultCalories;
            this.distance = distance;
            this.address = address;
            this.note = note;
        }
    }

    public static class CalorieSummaryResponse {
        public boolean ok;
        public CalorieSummaryData data;
        public ErrorBody error;
    }

    public static class CalorieSummaryData {
        public String date;
        public double totalCalories;
        public double dailyGoal;
        public double progress;
        public CalorieSourceTotals sourceTotals;
        public List<CalorieUserSummary> userSummaries;
        public Integer relationshipId;
        public List<MealRecord> records;
    }

    public static class CalorieSourceTotals {
        public double cook;
        public double eatOut;
        public double manual;
    }

    public static class CalorieUserSummary {
        public int userId;
        public String userName;
        public double totalCalories;
        public double dailyGoal;
        public double progress;
    }

    public static class MealRecord {
        public int id;
        public int userId;
        public String userName;
        public String mealType;
        public Integer recipeId;
        public Integer restaurantId;
        public String title;
        public double calories;
        public String calorieSource;
        public String note;
        public String eatenAt;
        public String createdAt;
    }

    public static class MealRecordRequest {
        public final int userId;
        public final String mealType;
        public final Integer recipeId;
        public final Integer restaurantId;
        public final String title;
        public final double calories;
        public final String calorieSource;
        public final String note;
        public final String eatenAt;

        public MealRecordRequest(int userId, String mealType, Integer recipeId, Integer restaurantId, String title,
                                 double calories, String calorieSource, String note, String eatenAt) {
            this.userId = userId;
            this.mealType = mealType;
            this.recipeId = recipeId;
            this.restaurantId = restaurantId;
            this.title = title;
            this.calories = calories;
            this.calorieSource = calorieSource;
            this.note = note;
            this.eatenAt = eatenAt;
        }
    }

    public static class CalorieGoalResponse {
        public boolean ok;
        public CalorieGoalData data;
        public ErrorBody error;
    }

    public static class CalorieGoalData {
        public int userId;
        public double dailyGoal;
        public String updatedAt;
    }

    public static class CalorieGoalRequest {
        public final int userId;
        public final double dailyGoal;

        public CalorieGoalRequest(int userId, double dailyGoal) {
            this.userId = userId;
            this.dailyGoal = dailyGoal;
        }
    }

    public static class NutritionItem {
        public Integer id;
        public String name;
        public double caloriesPerUnit;
        public String unit;
        public String category;
        public String createdAt;
        public String updatedAt;

        public NutritionItem() {
        }

        public NutritionItem(String name, double caloriesPerUnit, String unit, String category) {
            this.name = name;
            this.caloriesPerUnit = caloriesPerUnit;
            this.unit = unit;
            this.category = category;
        }
    }

    public static class NutritionResponse {
        public boolean ok;
        public NutritionData data;
        public ErrorBody error;
    }

    public static class NutritionData {
        public NutritionItem item;
    }

    public static class NutritionSearchResponse {
        public boolean ok;
        public NutritionSearchData data;
        public ErrorBody error;
    }

    public static class NutritionSearchData {
        public List<NutritionItem> items;
    }

    public static class AiAnalyzeRequest {
        public final int userId;
        public final java.util.List<AiChatMessage> messages;
        public AiAnalyzeRequest(int userId, java.util.List<AiChatMessage> messages) {
            this.userId = userId;
            this.messages = messages;
        }
    }

    public static class AiChatMessage {
        public final long id;
        public final String username;
        public final String content;
        public AiChatMessage(long id, String username, String content) {
            this.id = id;
            this.username = username;
            this.content = content;
        }
    }

    public static class AiAnalyzeResponse {
        public boolean ok;
        public AiAnalyzeData data;
        public ErrorBody error;
    }

    public static class AiAnalyzeData {
        public java.util.List<AiExtractionItem> extractions;
    }

    public static class AiExtractionItem {
        public int id;
        public String type;
        public java.util.Map<String, Object> data;
        public String status;
    }

    public static class AiExtractionActionRequest {
        public final int userId;
        public final java.util.Map<String, Object> overrides;
        public AiExtractionActionRequest(int userId) { this(userId, null); }
        public AiExtractionActionRequest(int userId, java.util.Map<String, Object> overrides) {
            this.userId = userId;
            this.overrides = overrides;
        }
    }

    public static class AiExtractionActionResponse {
        public boolean ok;
        public AiExtractionConfirmData data;
        public ErrorBody error;
    }

    public static class AiExtractionConfirmData {
        public int targetId;
        public String type;
    }

    // --- Asset models ---

    public static class AssetItemData {
        public int assetId;
        public int userId;
        public Integer relationshipId;
        public String name;
        public String category;
        public String imageUrl;
        public String originalImageUrl;
        public String purchaseDate;
        public Double purchasePrice;
        public Double currentValue;
        public String status;
        public String note;
        public String disposedAt;
        public String createdAt;
        public String updatedAt;
        public int holdDays;
        public double dailyCost;
        public double monthlyCost;
    }

    public static class AssetListData {
        public List<AssetItemData> items;
        public Integer relationshipId;
    }

    public static class AssetStatsData {
        public double totalValue;
        public int totalCount;
        public double dailyAvgCost;
        public double monthlyAvgCost;
        public List<CategoryBreakdown> categoryBreakdown;
        public StatusBreakdown statusBreakdown;
        public AssetItemData latestItem;
    }

    public static class CategoryBreakdown {
        public String category;
        public int count;
        public double totalValue;
    }

    public static class StatusBreakdown {
        public int active;
        public int idle;
        public int disposed;
    }

    public static class AssetCategoryData {
        public List<String> categories;
    }

    public static class AssetListResponse {
        public boolean ok;
        public String message;
        public AssetListData data;
        public ErrorBody error;
    }

    public static class AssetStatsResponse {
        public boolean ok;
        public String message;
        public AssetStatsData data;
        public ErrorBody error;
    }

    public static class AssetCategoryListResponse {
        public boolean ok;
        public String message;
        public AssetCategoryData data;
        public ErrorBody error;
    }

    public static class AssetMutationResponse {
        public boolean ok;
        public String message;
        public AssetMutationData data;
        public ErrorBody error;
    }

    public static class AssetMutationData {
        public int assetId;
    }

    public static class AssetStatusUpdateRequest {
        public final int userId;
        public final String status;

        public AssetStatusUpdateRequest(int userId, String status) {
            this.userId = userId;
            this.status = status;
        }
    }

    public static class CreateAssetRequest {
        public final int userId;
        public final String name;
        public final String category;
        public final String imageUrl;
        public final String originalImageUrl;
        public final String purchaseDate;
        public final Double purchasePrice;
        public final Double currentValue;
        public final String status;
        public final String note;

        public CreateAssetRequest(int userId, String name, String category, String imageUrl, String originalImageUrl,
                                  String purchaseDate, Double purchasePrice, Double currentValue, String status, String note) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.imageUrl = imageUrl;
            this.originalImageUrl = originalImageUrl;
            this.purchaseDate = purchaseDate;
            this.purchasePrice = purchasePrice;
            this.currentValue = currentValue;
            this.status = status;
            this.note = note;
        }
    }

    public static class RemoveBgResponse {
        public boolean ok;
        public String message;
        public RemoveBgData data;
        public ErrorBody error;
    }

    public static class RemoveBgData {
        public String imageUrl;
    }

    public static class PeriodRecordData {
        public int id;
        public int userId;
        public String userName;
        public String startDate;
        public String endDate;
        public String note;
        public String createdAt;
    }

    public static class PeriodListData {
        public List<PeriodRecordData> records;
        public Integer averageCycleDays;
        public String predictedNextStart;
        public Integer partnerAverageCycleDays;
        public String partnerPredictedNextStart;
        public Integer relationshipId;
    }

    public static class PeriodListResponse {
        public boolean ok;
        public PeriodListData data;
        public ErrorBody error;
    }

    public static class CreatePeriodRequest {
        public final int userId;
        public final String startDate;
        public final String note;

        public CreatePeriodRequest(int userId, String startDate, String note) {
            this.userId = userId;
            this.startDate = startDate;
            this.note = note;
        }
    }

    public static class UpdatePeriodRequest {
        public final int userId;
        public final String startDate;
        public final String endDate;
        public final String note;

        public UpdatePeriodRequest(int userId, String startDate, String endDate, String note) {
            this.userId = userId;
            this.startDate = startDate;
            this.endDate = endDate;
            this.note = note;
        }
    }

    /**
     * /api/me/overview 聚合响应。
     * 每个子字段复用对应接口的 response 类型；后端某子查询失败时该字段为 null。
     */
    public static class OverviewResponse {
        public boolean ok;
        public OverviewData data;
        public ErrorBody error;
    }

    public static class OverviewData {
        public int year;
        public int month;
        public BillsQueryResponse bills;
        public TodoListResponse todos;
        public InventoryListResponse inventory;
        public AssetStatsResponse assetStats;
        public PeriodListResponse period;
        public CoupleInfoResponse coupleInfo;
        public CalorieSummaryResponse calorie;
    }

    /**
     * coupleInfo 子响应：与 /api/auth/couple-info 的响应形状一致。
     */
    public static class CoupleInfoResponse {
        public boolean ok;
        public CoupleInfoData data;
    }

    public static class CoupleInfoData {
        public boolean hasCouple;
        public int partnerId;
        public String partnerName;
        public String partnerNickname;
        public String partnerAvatarUrl;
        public int relationshipId;
    }

    // --- Password account (账号保险箱) models ---

    public static class PasswordAccountItemData {
        public int accountId;
        public int userId;
        public String platformName;
        public String accountIdentifier;
        public String phone;
        public String email;
        public String websiteUrl;
        public String password;
        public String securityQuestion;
        public String securityAnswer;
        public String category;
        public String note;
        public int sortOrder;
        public String createdAt;
        public String updatedAt;
    }

    public static class PasswordAccountListData {
        public List<PasswordAccountItemData> items;
    }

    public static class PasswordAccountListResponse {
        public boolean ok;
        public String message;
        public PasswordAccountListData data;
        public ErrorBody error;
    }

    public static class PasswordAccountDetailResponse {
        public boolean ok;
        public String message;
        public PasswordAccountItemData data;
        public ErrorBody error;
    }

    public static class PasswordAccountMutationResponse {
        public boolean ok;
        public String message;
        public PasswordAccountMutationData data;
        public ErrorBody error;
    }

    public static class PasswordAccountMutationData {
        public int accountId;
    }

    public static class PasswordAccountCategoryData {
        public List<String> categories;
    }

    public static class PasswordAccountCategoryListResponse {
        public boolean ok;
        public String message;
        public PasswordAccountCategoryData data;
        public ErrorBody error;
    }

    public static class CreatePasswordAccountRequest {
        public final int userId;
        public final String platformName;
        public final String accountIdentifier;
        public final String phone;
        public final String email;
        public final String websiteUrl;
        public final String password;
        public final String securityQuestion;
        public final String securityAnswer;
        public final String category;
        public final String note;

        public CreatePasswordAccountRequest(int userId, String platformName, String accountIdentifier,
                                            String phone, String email, String websiteUrl, String password,
                                            String securityQuestion, String securityAnswer, String category, String note) {
            this.userId = userId;
            this.platformName = platformName;
            this.accountIdentifier = accountIdentifier;
            this.phone = phone;
            this.email = email;
            this.websiteUrl = websiteUrl;
            this.password = password;
            this.securityQuestion = securityQuestion;
            this.securityAnswer = securityAnswer;
            this.category = category;
            this.note = note;
        }
    }

    public static class UpdatePasswordAccountRequest {
        public final int userId;
        public final String platformName;
        public final String accountIdentifier;
        public final String phone;
        public final String email;
        public final String websiteUrl;
        public final String password;
        public final String securityQuestion;
        public final String securityAnswer;
        public final String category;
        public final String note;

        public UpdatePasswordAccountRequest(int userId, String platformName, String accountIdentifier,
                                            String phone, String email, String websiteUrl, String password,
                                            String securityQuestion, String securityAnswer, String category, String note) {
            this.userId = userId;
            this.platformName = platformName;
            this.accountIdentifier = accountIdentifier;
            this.phone = phone;
            this.email = email;
            this.websiteUrl = websiteUrl;
            this.password = password;
            this.securityQuestion = securityQuestion;
            this.securityAnswer = securityAnswer;
            this.category = category;
            this.note = note;
        }
    }
}
