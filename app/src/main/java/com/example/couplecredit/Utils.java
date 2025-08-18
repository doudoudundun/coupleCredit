package com.example.couplecredit;

import static java.security.AccessController.getContext;

import android.app.DatePickerDialog;
import android.content.Context;
import android.net.Uri;
import android.widget.DatePicker;
import android.widget.Toast;

import java.util.Calendar;

public final class Utils {
    public static void insertBill(Context context,int userId, String title, String type, double amount, String date, String time, int incomeType) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(BillDatabaseHelper.USER_ID, userId);
        values.put(BillDatabaseHelper.COLUMN_TITLE, title);
        values.put(BillDatabaseHelper.COLUMN_TYPE, type);
        values.put(BillDatabaseHelper.COLUMN_AMOUNT, amount);
        values.put(BillDatabaseHelper.COLUMN_DATE, date);
        values.put(BillDatabaseHelper.COLUMN_TIME, time);
        values.put(BillDatabaseHelper.COLUMN_INCOME_TYPE, incomeType);

        context.getContentResolver().insert(Uri.parse(BillProvider.CONTENT_URI + "/bills"), values);
    }
    //加入多用户之后需要切分逻辑
    public static int getUserId(String username){
        if(username.equals("自己")){
            return 1;
        }
        else if(username.equals("对方")){
            return 2;
        }
        else{
            return 3;
        }
    }

    public static int deleteBill(Context context, BillBean bill) {
        String selection = BillDatabaseHelper.USER_ID + "=? AND " +
                          BillDatabaseHelper.COLUMN_TYPE + "=? AND " +
                          BillDatabaseHelper.COLUMN_AMOUNT + "=? AND " +
                          BillDatabaseHelper.COLUMN_DATE + "=? AND " +
                          BillDatabaseHelper.COLUMN_TIME + "=? AND " +
                          BillDatabaseHelper.COLUMN_INCOME_TYPE + "=?";
        
        String dateString = String.format("%04d-%02d-%02d", bill.getYear(), bill.getMonth(), bill.getDay());
        
        String[] selectionArgs = {
            String.valueOf(bill.getUserId()),
            bill.getCategoryName(),
            String.valueOf(bill.getFare()),
            dateString,
            bill.getTime(),
            String.valueOf(bill.getIncomeType())
        };
        
        return context.getContentResolver().delete(
            Uri.parse(BillProvider.CONTENT_URI + "/bills"),
            selection,
            selectionArgs
        );
    }
    
    public static int updateBill(Context context, BillBean bill, String newDate, double newFare, String newNoteContent, String newTime) {
        // 构建WHERE条件，用于定位要更新的账单
        String selection = BillDatabaseHelper.USER_ID + "=? AND " +
                          BillDatabaseHelper.COLUMN_TYPE + "=? AND " +
                          BillDatabaseHelper.COLUMN_AMOUNT + "=? AND " +
                          BillDatabaseHelper.COLUMN_DATE + "=? AND " +
                          BillDatabaseHelper.COLUMN_TIME + "=? AND " +
                          BillDatabaseHelper.COLUMN_INCOME_TYPE + "=?";
        
        String originalDateString = String.format("%04d-%02d-%02d", bill.getYear(), bill.getMonth(), bill.getDay());
        
        String[] selectionArgs = {
            String.valueOf(bill.getUserId()),
            bill.getCategoryName(),
            String.valueOf(bill.getFare()),
            originalDateString,
            bill.getTime(),
            String.valueOf(bill.getIncomeType())
        };
        
        // 构建要更新的值
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(BillDatabaseHelper.COLUMN_DATE, newDate);
        values.put(BillDatabaseHelper.COLUMN_AMOUNT, newFare);
        values.put(BillDatabaseHelper.COLUMN_TITLE, newNoteContent);
        values.put(BillDatabaseHelper.COLUMN_TIME, newTime);
        
        return context.getContentResolver().update(
            Uri.parse(BillProvider.CONTENT_URI + "/bills"),
            values,
            selection,
            selectionArgs
        );
    }

    public interface DatePickerCallback {
        void onDateSelected(String formattedDate);
    }
    
    public static void showDatePicker(Context context, String dateString, DatePickerCallback callback) {
        Calendar selectedDate = Calendar.getInstance();
        int year = selectedDate.get(Calendar.YEAR);
        int month = selectedDate.get(Calendar.MONTH);
        int day = selectedDate.get(Calendar.DAY_OF_MONTH);
        
        // 解析当前日期文本
        if (dateString != null && !dateString.isEmpty()) {
            try {
                String[] dateParts = dateString.split("-");
                if (dateParts.length == 3) {
                    year = Integer.parseInt(dateParts[0]);
                    month = Integer.parseInt(dateParts[1]) - 1; // Calendar月份从0开始
                    day = Integer.parseInt(dateParts[2]);
                }
            } catch (Exception e) {
                // 使用当前日期作为默认值
            }
        }
        
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                context,
                (view, selectedYear, selectedMonth, selectedDay) -> {
                    // 格式化选择的日期为YYYY-MM-DD格式
                    String formattedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay);
                    if (callback != null) {
                        callback.onDateSelected(formattedDate);
                    }
                },
                year, month, day
        );

        datePickerDialog.setTitle("选择日期");
        datePickerDialog.show();
        
        // 设置按钮颜色
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE).setTextColor(
                    context.getResources().getColor(android.R.color.holo_blue_dark));
        }
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE).setTextColor(
                    context.getResources().getColor(android.R.color.holo_red_dark));
        }
    }


}
