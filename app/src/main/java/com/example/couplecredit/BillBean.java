package com.example.couplecredit;
/**
 * Class BillBean
 * @author qinyang.li、biru.zhang
 * @date 2025-08-06
 * billitem: 账单项
 * 账单金额：int fare , 账单日期：int year, 账单日期：int month, 账单日期：int day, 账单用户ID：int userId(1为邀请者，2为被邀请者，3为共同开支),
 * 账单类别名称：string categoryName, 账单类别描述：string categoryDesc, 账单图标：int iconResId(和账单类别对应)
 */
public final class BillBean {
    //账单ID
    private long billId;
    //费用
    private double fare;
    //消费日期：年
    private int year;
    //消费日期：月
    private int month;
    private int day;
    private int owner;
    private int userId;
    private String categoryName;
    private String categoryDesc;
    private int iconResId;
    private int incomeType; // 0=支出，1=收入
    private String time; // 时间 HH:mm:ss
    private String title; // 备注

    public BillBean(long billId, double fare, int year, int month, int day, int userId, String categoryName,
                    String categoryDesc, int iconResId, int incomeType, String time, String title) {
        this.billId = billId;
        this.fare = fare;
        this.year = year;
        this.month = month;
        this.day = day;
        this.userId = userId;
        this.categoryName = categoryName;
        this.categoryDesc = categoryDesc;
        this.iconResId = iconResId;
        this.incomeType = incomeType;
        this.time = time;
        this.title = title;
    }

    public BillBean(long billId, double fare, int year, int month, int day, int owner, int userId, String categoryName,
                    String categoryDesc, int iconResId, int incomeType, String time, String title) {
        this.billId = billId;
        this.fare = fare;
        this.year = year;
        this.month = month;
        this.day = day;
        this.owner = owner;
        this.userId = userId;
        this.categoryName = categoryName;
        this.categoryDesc = categoryDesc;
        this.iconResId = iconResId;
        this.incomeType = incomeType;
        this.time = time;
        this.title = title;
    }

    public int getIconResId() {
        return iconResId;
    }

    public void setIconResId(int iconResId) {
        this.iconResId = iconResId;
    }

    public double getFare() {
        return fare;
    }

    public void setFare(double fare) {
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

    public int getIncomeType() {
        return incomeType;
    }

    public void setIncomeType(int incomeType) {
        this.incomeType = incomeType;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getBillId() {
        return billId;
    }

    public void setBillId(long billId) {
        this.billId = billId;
    }

    public int getOwner() {
        return owner;
    }

    public void setOwner(int owner) {
        this.owner = owner;
    }
}
