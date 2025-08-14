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
}
