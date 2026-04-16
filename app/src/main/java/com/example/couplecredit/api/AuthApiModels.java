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
        public final String title;
        public final String type;
        public final double amount;
        public final String date;
        public final String time;
        public final int incomeType;

        public CreateBillRequest(int userId, String billOwner, String title, String type, double amount, String date, String time, int incomeType) {
            this.userId = userId;
            this.billOwner = billOwner;
            this.title = title;
            this.type = type;
            this.amount = amount;
            this.date = date;
            this.time = time;
            this.incomeType = incomeType;
        }
    }
}
