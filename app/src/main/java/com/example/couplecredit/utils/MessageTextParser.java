package com.example.couplecredit.utils;

import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能文本解析工具类
 * 用于从聊天消息中提取记账相关信息
 * 
 * 功能包括：
 * 1. 金额提取：识别数字和货币符号
 * 2. 时间解析：识别日期格式，默认当前时间
 * 3. 账单类型匹配：根据关键词匹配分类
 * 4. 收支类型判断：根据上下文判断收入/支出
 */
public class MessageTextParser {
    
    private static final String TAG = "MessageTextParser";
    
    /**
     * 解析结果数据类
     */
    public static class ParseResult {
        private double amount = 0.0;
        private String date = "";
        private String time = "";
        private String category = "";
        private int incomeType = 0; // 0=支出，1=收入
        private String note = "";
        private boolean hasAmount = false;
        private boolean hasDate = false;
        private boolean hasCategory = false;
        
        // Getters and Setters
        public double getAmount() { return amount; }
        public void setAmount(double amount) { 
            this.amount = amount;
            this.hasAmount = true;
        }
        
        public String getDate() { return date; }
        public void setDate(String date) { 
            this.date = date;
            this.hasDate = true;
        }
        
        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        
        public String getCategory() { return category; }
        public void setCategory(String category) { 
            this.category = category;
            this.hasCategory = true;
        }
        
        public int getIncomeType() { return incomeType; }
        public void setIncomeType(int incomeType) { this.incomeType = incomeType; }
        
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        
        public boolean hasAmount() { return hasAmount; }
        public boolean hasDate() { return hasDate; }
        public boolean hasCategory() { return hasCategory; }
    }
    
    // 金额匹配正则表达式
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?:￥|¥|\\$|元|块)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|￥|¥|\\$)?",
        Pattern.CASE_INSENSITIVE
    );
    
    // 日期匹配正则表达式
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日]?|" +
        "\\d{1,2}[-/月]\\d{1,2}[日]?|" +
        "今天|昨天|明天|前天|后天)",
        Pattern.CASE_INSENSITIVE
    );
    
    // 时间匹配正则表达式
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(\\d{1,2}[:：]\\d{1,2}(?:[:：]\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE
    );
    
    // 移除本地的分类关键词映射，改用CategoryIconMapper统一管理
    
    // 收入关键词
    private static final String INCOME_KEYWORDS = "工资|奖金|分红|利息|退款|返现|收入|赚|得到|获得|中奖|兼职|外快";
    
    // 支出关键词
    private static final String EXPENSE_KEYWORDS = "花|买|付|支付|消费|花费|支出|缴费|交费|报销";
    
    /**
     * 解析聊天消息文本
     * @param messageText 消息文本
     * @return 解析结果
     */
    public static ParseResult parseMessage(String messageText) {
        if (messageText == null || messageText.trim().isEmpty()) {
            return new ParseResult();
        }
        
        ParseResult result = new ParseResult();
        String text = messageText.trim();
        
        // 提取金额
        extractAmount(text, result);
        
        // 提取日期和时间
        extractDateTime(text, result);
        
        // 匹配账单分类
        extractCategory(text, result);
        
        // 判断收支类型
        extractIncomeType(text, result);
        
        // 设置备注（原始消息文本）
        result.setNote(text);
        
        // 设置默认时间（如果没有解析到）
        if (!result.hasDate()) {
            setDefaultDateTime(result);
        }
        
        Log.d(TAG, "解析结果: 金额=" + result.getAmount() + ", 分类=" + result.getCategory() + 
              ", 收支类型=" + result.getIncomeType() + ", 日期=" + result.getDate());
        
        return result;
    }
    
    /**
     * 提取金额信息
     */
    private static void extractAmount(String text, ParseResult result) {
        Matcher matcher = AMOUNT_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                String amountStr = matcher.group(1);
                double amount = Double.parseDouble(amountStr);
                if (amount > 0) {
                    result.setAmount(amount);
                    Log.d(TAG, "提取到金额: " + amount);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "金额解析失败: " + matcher.group(1));
            }
        }
    }
    
    /**
     * 提取日期和时间信息
     */
    private static void extractDateTime(String text, ParseResult result) {
        // 提取日期
        Matcher dateMatcher = DATE_PATTERN.matcher(text);
        if (dateMatcher.find()) {
            String dateStr = dateMatcher.group(1);
            String parsedDate = parseDateString(dateStr);
            if (parsedDate != null) {
                result.setDate(parsedDate);
                Log.d(TAG, "提取到日期: " + parsedDate);
            }
        }
        
        // 提取时间
        Matcher timeMatcher = TIME_PATTERN.matcher(text);
        if (timeMatcher.find()) {
            String timeStr = timeMatcher.group(1);
            String parsedTime = parseTimeString(timeStr);
            if (parsedTime != null) {
                result.setTime(parsedTime);
                Log.d(TAG, "提取到时间: " + parsedTime);
            }
        }
    }
    
    /**
     * 解析日期字符串
     */
    private static String parseDateString(String dateStr) {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        
        try {
            // 处理相对日期
            switch (dateStr) {
                case "今天":
                    return outputFormat.format(calendar.getTime());
                case "昨天":
                    calendar.add(Calendar.DAY_OF_MONTH, -1);
                    return outputFormat.format(calendar.getTime());
                case "明天":
                    calendar.add(Calendar.DAY_OF_MONTH, 1);
                    return outputFormat.format(calendar.getTime());
                case "前天":
                    calendar.add(Calendar.DAY_OF_MONTH, -2);
                    return outputFormat.format(calendar.getTime());
                case "后天":
                    calendar.add(Calendar.DAY_OF_MONTH, 2);
                    return outputFormat.format(calendar.getTime());
            }
            
            // 处理具体日期格式
            String normalizedDate = dateStr.replaceAll("[年月日/-]", "-")
                                          .replaceAll("-+", "-")
                                          .replaceAll("^-|-$", "");
            
            // 尝试不同的日期格式
            String[] dateFormats = {
                "yyyy-MM-dd", "MM-dd", "M-d", "yyyy-M-d", "yyyy-MM-d", "yyyy-M-dd"
            };
            
            for (String format : dateFormats) {
                try {
                    SimpleDateFormat inputFormat = new SimpleDateFormat(format, Locale.getDefault());
                    Date date = inputFormat.parse(normalizedDate);
                    
                    // 如果只有月日，补充当前年份
                    if (!format.contains("yyyy")) {
                        Calendar parsedCalendar = Calendar.getInstance();
                        parsedCalendar.setTime(date);
                        parsedCalendar.set(Calendar.YEAR, calendar.get(Calendar.YEAR));
                        date = parsedCalendar.getTime();
                    }
                    
                    return outputFormat.format(date);
                } catch (ParseException ignored) {
                    // 继续尝试下一个格式
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "日期解析失败: " + dateStr, e);
        }
        
        return null;
    }
    
    /**
     * 解析时间字符串
     */
    private static String parseTimeString(String timeStr) {
        try {
            String normalizedTime = timeStr.replace("：", ":");
            
            // 补充秒数（如果没有）
            if (normalizedTime.split(":").length == 2) {
                normalizedTime += ":00";
            }
            
            // 验证时间格式
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            timeFormat.setLenient(false);
            Date time = timeFormat.parse(normalizedTime);
            
            return timeFormat.format(time);
        } catch (ParseException e) {
            Log.w(TAG, "时间解析失败: " + timeStr, e);
            return null;
        }
    }
    
    /**
     * 提取账单分类
     */
    private static void extractCategory(String text, ParseResult result) {
        String category = CategoryIconMapper.extractCategoryFromText(text);
        if (category != null) {
            result.setCategory(category);
            Log.d(TAG, "匹配到分类: " + category);
        }
    }
    
    /**
     * 判断收支类型
     */
    private static void extractIncomeType(String text, ParseResult result) {
        Pattern incomePattern = Pattern.compile("(" + INCOME_KEYWORDS + ")", Pattern.CASE_INSENSITIVE);
        Pattern expensePattern = Pattern.compile("(" + EXPENSE_KEYWORDS + ")", Pattern.CASE_INSENSITIVE);
        
        boolean hasIncomeKeyword = incomePattern.matcher(text).find();
        boolean hasExpenseKeyword = expensePattern.matcher(text).find();
        
        if (hasIncomeKeyword && !hasExpenseKeyword) {
            result.setIncomeType(1); // 收入
            Log.d(TAG, "判断为收入");
        } else {
            result.setIncomeType(0); // 默认支出
            Log.d(TAG, "判断为支出");
        }
    }
    
    /**
     * 设置默认日期和时间
     */
    private static void setDefaultDateTime(ParseResult result) {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        
        result.setDate(dateFormat.format(calendar.getTime()));
        if (result.getTime().isEmpty()) {
            result.setTime(timeFormat.format(calendar.getTime()));
        }
    }
    
    /**
     * 验证解析结果的完整性
     * @param result 解析结果
     * @return 验证通过返回true
     */
    public static boolean validateParseResult(ParseResult result) {
        // 金额必须大于0
        if (!result.hasAmount() || result.getAmount() <= 0) {
            Log.w(TAG, "验证失败: 金额无效");
            return false;
        }
        
        // 日期不能为空
        if (result.getDate().isEmpty()) {
            Log.w(TAG, "验证失败: 日期为空");
            return false;
        }
        
        return true;
    }
}