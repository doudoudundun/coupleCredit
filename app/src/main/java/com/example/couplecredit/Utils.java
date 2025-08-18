package com.example.couplecredit;

import android.content.Context;
import android.net.Uri;

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
}
