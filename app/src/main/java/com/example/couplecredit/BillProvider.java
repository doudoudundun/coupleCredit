package com.example.couplecredit;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

public class BillProvider extends ContentProvider {
    public static final String AUTHORITY = "com.example.couplecredit.billprovider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    private static final int BILLS = 1;
    private static final int BILL_ID = 2;
    private BillDatabaseHelper mDbHelper;
    private static UriMatcher mUriMatcher;

    static {
        mUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mUriMatcher.addURI(AUTHORITY, "bills", BILLS);
        mUriMatcher.addURI(AUTHORITY, "bills/#", BILL_ID);
    }
    @Override
    public boolean onCreate() {
        mDbHelper = new BillDatabaseHelper(getContext());
        return true;
    }
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor cursor;
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                cursor = db.query(BillDatabaseHelper.TABLE_BILLS,
                        projection, selection, selectionArgs, null, null, sortOrder);
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + "=?";
                selectionArgs = new String[]{String.valueOf(ContentUris.parseId(uri))};
                cursor = db.query(BillDatabaseHelper.TABLE_BILLS,
                        projection, selection, selectionArgs, null, null, sortOrder);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
        return cursor;
    }
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        long id;
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                id = db.insert(BillDatabaseHelper.TABLE_BILLS, null, values);
                if (id > 0){
                    Uri newUri = ContentUris.withAppendedId(BillProvider.CONTENT_URI, id);
                    getContext().getContentResolver().notifyChange(newUri, null);//通知数据已改变
                    return newUri;
                }
                throw new SQLException("Failed to insert row into " + uri);
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        int rowsDeleted = 0;
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                rowsDeleted = db.delete(BillDatabaseHelper.TABLE_BILLS, selection, selectionArgs);
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + " = ?";
                selectionArgs = new String[] { String.valueOf(ContentUris.parseId(uri)) };
                rowsDeleted = db.delete(BillDatabaseHelper.TABLE_BILLS, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
        if (rowsDeleted > 0) {//通知监听者
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return rowsDeleted;
    }
    @Override
    public int update(Uri uri, ContentValues values, String selection,String[] selectionArgs){
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        int rowsUpdated = 0;
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                rowsUpdated = db.update(BillDatabaseHelper.TABLE_BILLS, values, selection, selectionArgs);
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + "=?";
                selectionArgs = new String[]{String.valueOf(ContentUris.parseId(uri))};
                rowsUpdated = db.update(BillDatabaseHelper.TABLE_BILLS, values, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
        if (rowsUpdated > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return rowsUpdated;
    }
    @Override
    public String getType(Uri uri) {
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".bills";
            case BILL_ID:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".bills";
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }
}
