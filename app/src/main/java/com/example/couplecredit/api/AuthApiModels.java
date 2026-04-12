package com.example.couplecredit.api;

public class AuthApiModels {
    public static class AuthSuccessData {
        public int userId;
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

    public static class ErrorBody {
        public String code;
        public String message;
    }

    public static class AuthResponse {
        public boolean ok;
        public String message;
        public AuthSuccessData data;
        public ErrorBody error;
    }

    public static class BillResponse {
        public boolean ok;
        public String message;
        public BillData data;
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
