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
        // 由于远程数据库操作是异步的，这里返回一个空的Cursor
        // 实际的查询操作需要通过queryBillsAsync方法进行
        String[] columns = {BillDatabaseHelper.COLUMN_ID, "relationship_id", "owner", BillDatabaseHelper.USER_ID, 
                           BillDatabaseHelper.COLUMN_TITLE, BillDatabaseHelper.COLUMN_TYPE, BillDatabaseHelper.COLUMN_AMOUNT, 
                           BillDatabaseHelper.COLUMN_DATE, BillDatabaseHelper.COLUMN_TIME, BillDatabaseHelper.COLUMN_INCOME_TYPE};
        return new android.database.MatrixCursor(columns);
    }
    
    // 异步查询方法
    public void queryBillsAsync(Uri uri, String[] projection, String selection,
                                String[] selectionArgs, String sortOrder, BillQueryResultCallback callback) {
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                mDbHelper.queryBills(selection, selectionArgs, sortOrder, new BillDatabaseHelper.BillQueryCallback() {
                    @Override
                    public void onQuerySuccess(Cursor cursor) {
                        callback.onQueryResult(cursor, null);
                    }
                    
                    @Override
                    public void onQueryError(String error) {
                        callback.onQueryResult(null, error);
                    }
                });
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + "=?";
                selectionArgs = new String[]{String.valueOf(ContentUris.parseId(uri))};
                mDbHelper.queryBills(selection, selectionArgs, sortOrder, new BillDatabaseHelper.BillQueryCallback() {
                    @Override
                    public void onQuerySuccess(Cursor cursor) {
                        callback.onQueryResult(cursor, null);
                    }
                    
                    @Override
                    public void onQueryError(String error) {
                        callback.onQueryResult(null, error);
                    }
                });
                break;
            default:
                callback.onQueryResult(null, "Unknown URI: " + uri);
        }
    }
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // 由于远程数据库操作是异步的，这里返回一个临时URI
        // 实际的插入操作需要通过insertBillAsync方法进行
        return ContentUris.withAppendedId(BillProvider.CONTENT_URI, 0);
    }
    
    // 异步插入方法
    public void insertBillAsync(Uri uri, ContentValues values, BillInsertResultCallback callback) {
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                mDbHelper.insertBill(values, new BillDatabaseHelper.BillInsertCallback() {
                    @Override
                    public void onInsertSuccess(long id) {
                        Uri newUri = ContentUris.withAppendedId(BillProvider.CONTENT_URI, id);
                        getContext().getContentResolver().notifyChange(newUri, null);
                        callback.onInsertResult(newUri, null);
                    }
                    
                    @Override
                    public void onInsertError(String error) {
                        callback.onInsertResult(null, error);
                    }
                });
                break;
            default:
                callback.onInsertResult(null, "Unknown URI: " + uri);
        }
    }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // 由于远程数据库操作是异步的，这里返回0
        // 实际的删除操作需要通过deleteBillAsync方法进行
        return 0;
    }
    
    // 异步删除方法
    public void deleteBillAsync(Uri uri, String selection, String[] selectionArgs, BillDeleteResultCallback callback) {
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                mDbHelper.deleteBill(selection, selectionArgs, new BillDatabaseHelper.BillDeleteCallback() {
                    @Override
                    public void onDeleteSuccess(int rowsDeleted) {
                        if (rowsDeleted > 0) {
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                        callback.onDeleteResult(rowsDeleted, null);
                    }
                    
                    @Override
                    public void onDeleteError(String error) {
                        callback.onDeleteResult(0, error);
                    }
                });
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + " = ?";
                selectionArgs = new String[] { String.valueOf(ContentUris.parseId(uri)) };
                mDbHelper.deleteBill(selection, selectionArgs, new BillDatabaseHelper.BillDeleteCallback() {
                    @Override
                    public void onDeleteSuccess(int rowsDeleted) {
                        if (rowsDeleted > 0) {
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                        callback.onDeleteResult(rowsDeleted, null);
                    }
                    
                    @Override
                    public void onDeleteError(String error) {
                        callback.onDeleteResult(0, error);
                    }
                });
                break;
            default:
                callback.onDeleteResult(0, "Unknown URI: " + uri);
        }
    }
    @Override
    public int update(Uri uri, ContentValues values, String selection,String[] selectionArgs){
        // 由于远程数据库操作是异步的，这里返回0
        // 实际的更新操作需要通过updateBillAsync方法进行
        return 0;
    }
    
    // 异步更新方法
    public void updateBillAsync(Uri uri, ContentValues values, String selection, String[] selectionArgs, BillUpdateResultCallback callback) {
        switch (mUriMatcher.match(uri)) {
            case BILLS:
                mDbHelper.updateBill(values, selection, selectionArgs, new BillDatabaseHelper.BillUpdateCallback() {
                    @Override
                    public void onUpdateSuccess(int rowsUpdated) {
                        if (rowsUpdated > 0) {
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                        callback.onUpdateResult(rowsUpdated, null);
                    }
                    
                    @Override
                    public void onUpdateError(String error) {
                        callback.onUpdateResult(0, error);
                    }
                });
                break;
            case BILL_ID:
                selection = BillDatabaseHelper.COLUMN_ID + "=?";
                selectionArgs = new String[]{String.valueOf(ContentUris.parseId(uri))};
                mDbHelper.updateBill(values, selection, selectionArgs, new BillDatabaseHelper.BillUpdateCallback() {
                    @Override
                    public void onUpdateSuccess(int rowsUpdated) {
                        if (rowsUpdated > 0) {
                            getContext().getContentResolver().notifyChange(uri, null);
                        }
                        callback.onUpdateResult(rowsUpdated, null);
                    }
                    
                    @Override
                    public void onUpdateError(String error) {
                        callback.onUpdateResult(0, error);
                    }
                });
                break;
            default:
                callback.onUpdateResult(0, "Unknown URI: " + uri);
        }
    }
    
    // 回调接口
    public interface BillQueryResultCallback {
        void onQueryResult(Cursor cursor, String error);
    }
    
    public interface BillInsertResultCallback {
        void onInsertResult(Uri uri, String error);
    }
    
    public interface BillDeleteResultCallback {
        void onDeleteResult(int rowsDeleted, String error);
    }
    
    public interface BillUpdateResultCallback {
        void onUpdateResult(int rowsUpdated, String error);
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
