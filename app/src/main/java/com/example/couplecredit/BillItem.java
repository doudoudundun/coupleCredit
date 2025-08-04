package com.example.couplecredit;

public class BillItem {
    private String categoryName;
    private String categoryDesc;
    private String amount;
    private int iconResId;

    public BillItem(String categoryName, String categoryDesc, String amount, int iconResId) {
        this.categoryName = categoryName;
        this.categoryDesc = categoryDesc;
        this.amount = amount;
        this.iconResId = iconResId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryDesc() {
        return categoryDesc;
    }

    public void setCategoryDesc(String categoryDesc) {
        this.categoryDesc = categoryDesc;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public int getIconResId() {
        return iconResId;
    }

    public void setIconResId(int iconResId) {
        this.iconResId = iconResId;
    }
}