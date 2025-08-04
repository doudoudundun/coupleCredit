package com.example.couplecredit;

public final class BillBean {
    //费用
    private int fare;
    //消费日期：年
    private int year;
    //消费日期：月
    private int month;
    private int day;
    private int userId;
    private String categoryName;
    private String categoryDesc;
    private int iconResId;

    public BillBean(int fare, int year, int month, int day, int userId, String categoryName,
                    String categoryDesc, int iconResId) {
        this.fare = fare;
        this.year = year;
        this.month = month;
        this.day = day;
        this.userId = userId;
        this.categoryName = categoryName;
        this.categoryDesc = categoryDesc;
        this.iconResId = iconResId;
    }

    public int getIconResId() {
        return iconResId;
    }

    public void setIconResId(int iconResId) {
        this.iconResId = iconResId;
    }

    public int getFare() {
        return fare;
    }

    public void setFare(int fare) {
        this.fare = fare;
    }

    public int getMonth() {
        return month;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getDay() {
        return day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName)  {
        this.categoryName = categoryName;
    }

    public String getCategoryDesc() {
        return categoryDesc;
    }

    public void setCategoryDesc(String categoryDesc) {
        this.categoryDesc = categoryDesc;
    }
}
