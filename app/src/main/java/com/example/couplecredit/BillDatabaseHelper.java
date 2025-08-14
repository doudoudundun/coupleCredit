package com.example.couplecredit;

import static android.app.DownloadManager.COLUMN_ID;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class BillDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "bills.db";
    private static final int DATABASE_VERSION = 3;
    public static final String TABLE_BILLS = "bills";
    public static final String CATEGORY_TABLE = "category";
    public static final String COLUMN_ID = "_id"; //账单ID
    public static final String USER_ID = "userId"; //用户 ID ： 1、2 ， 1为邀请人，2为被邀请人
    public static final String COLUMN_TITLE = "title";//账单备注
    public static final String COLUMN_TYPE = "type";//账单类型
    public static final String COLUMN_AMOUNT = "amount";//账单金额
    public static final String COLUMN_DATE = "date";//账单日期 日期格式：2023-01-01
    public static final String COLUMN_TIME = "time";//账单时间 时间格式：HH:mm:ss
    public static final String COLUMN_INCOME_TYPE = "income_type";//收入支出类型：0=支出，1=收入



    public BillDatabaseHelper(@Nullable Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        //创建账单表
        db.execSQL("CREATE TABLE " + TABLE_BILLS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + USER_ID + " INTEGER NOT NULL, "
                + COLUMN_TITLE + " TEXT NOT NULL, "
                + COLUMN_TYPE + " TEXT NOT NULL, "
                + COLUMN_AMOUNT + " REAL NOT NULL, "
                + COLUMN_DATE + " TEXT NOT NULL, "
                + COLUMN_TIME + " TEXT NOT NULL DEFAULT '00:00:00', "
                + COLUMN_INCOME_TYPE + " INTEGER NOT NULL DEFAULT 0"
                + ");");
        //账单种类和账单id对照表
        db.execSQL("CREATE TABLE " + CATEGORY_TABLE + " ("
                + "cate_id" + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_TYPE + " TEXT NOT NULL"
                + ");");
    }
    

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // 添加收入支出类型字段
            db.execSQL("ALTER TABLE " + TABLE_BILLS + " ADD COLUMN " + COLUMN_INCOME_TYPE + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            // 添加时间字段，默认值为00:00:00
            db.execSQL("ALTER TABLE " + TABLE_BILLS + " ADD COLUMN " + COLUMN_TIME + " TEXT NOT NULL DEFAULT '00:00:00'");
        }
    }
}