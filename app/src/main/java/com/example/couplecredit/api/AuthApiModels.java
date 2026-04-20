package com.example.couplecredit.api;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class AuthApiModels {
    public static class AuthSuccessData {
        @SerializedName(value = "userId", alternate = {"id", "user_id"})
        public int userId;
        @SerializedName(value = "username", alternate = {"userName", "name"})
        public String username;
        public String email;
    }

    public static class BillData {
        public long billId;
        public Integer relationshipId;
        public Integer sharedPlanId;
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
        public String createdAt;
        public String updatedAt;
        public String lastConsumedAt;
        public String note;
        public String aiImagePrompt;
        public boolean isLowStock;
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
        public final String imageUrl;
        public final String note;
        public final String aiImagePrompt;

        public CreateInventoryRequest(int userId, String name, String category, double quantity, String unit, double threshold, String imageUrl, String note, String aiImagePrompt) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.unit = unit;
            this.threshold = threshold;
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
        public final String imageUrl;
        public final String note;
        public final String aiImagePrompt;

        public UpdateInventoryRequest(int userId, String name, String category, Double quantity, String unit, Double threshold, String imageUrl, String note, String aiImagePrompt) {
            this.userId = userId;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.unit = unit;
            this.threshold = threshold;
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
        public java.util.List<IngredientData> ingredients;

        public CreateRecipeRequest(int userId, String title, String description, String imageUrl, String steps, Integer categoryId, java.util.List<IngredientData> ingredients) {
            this.userId = userId;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.steps = steps;
            this.categoryId = categoryId;
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
        public java.util.List<IngredientData> ingredients;

        public UpdateRecipeRequest(int userId, String title, String description, String imageUrl, String steps, Integer categoryId, java.util.List<IngredientData> ingredients) {
            this.userId = userId;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.steps = steps;
            this.categoryId = categoryId;
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
}
