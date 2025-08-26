package com.example.couplecredit;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.AsyncTask;
import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BillDatabaseHelper {
    private static final String TAG = "BillDatabaseHelper";
    
    // 远程数据库连接配置
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "demodb";
    private static final String DB_USER = "demodb";
    private static final String DB_PASSWORD = "root";
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    // 表和字段常量
    public static final String TABLE_BILLS = "bills";
    public static final String CATEGORY_TABLE = "category";
    public static final String COLUMN_ID = "_id"; //账单ID
    public static final String USER_ID = "userId"; //用户 ID
    public static final String COLUMN_TITLE = "title";//账单备注
    public static final String COLUMN_TYPE = "type";//账单类型
    public static final String COLUMN_AMOUNT = "amount";//账单金额
    public static final String COLUMN_DATE = "date";//账单日期 日期格式：2023-01-01
    public static final String COLUMN_TIME = "time";//账单时间 时间格式：HH:mm:ss
    public static final String COLUMN_INCOME_TYPE = "income_type";//收入支出类型：0=支出，1=收入
    
    private Context context;



    public BillDatabaseHelper(Context context) {
        this.context = context;
    }
    
    // 获取数据库连接
    private Connection getConnection() throws ClassNotFoundException, SQLException {
        Log.d(TAG, "尝试连接数据库: " + DB_URL);
        Class.forName("com.mysql.jdbc.Driver");
        Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
        Log.d(TAG, "数据库连接成功");
        return connection;
    }
    
    // 关闭数据库资源
    private void closeResources(Connection connection, PreparedStatement statement, ResultSet resultSet) {
        try {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
            if (connection != null) connection.close();
        } catch (SQLException e) {
            Log.e(TAG, "关闭数据库资源失败", e);
        }
    }
    
    // 处理数据库异常
    private String handleException(Exception e) {
        if (e instanceof ClassNotFoundException) {
            return "数据库驱动未找到: " + e.getMessage();
        } else if (e instanceof SQLException) {
            return "数据库操作失败: " + e.getMessage();
        } else {
            return "未知错误: " + e.getMessage();
        }
    }
    
    // 查询账单数据
    public void queryBills(String selection, String[] selectionArgs, String sortOrder, BillQueryCallback callback) {
        new AsyncTask<Void, Void, Cursor>() {
            private String errorMessage = "";
            
            @Override
            protected Cursor doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                ResultSet resultSet = null;
                
                try {
                    connection = getConnection();
                    
                    StringBuilder sql = new StringBuilder("SELECT bill_id as _id, relationship_id, owner, user_id as userId, title, type, amount, date, time, income_type FROM bills");
                    
                    if (selection != null && !selection.isEmpty()) {
                        sql.append(" WHERE ").append(selection);
                    }
                    
                    if (sortOrder != null && !sortOrder.isEmpty()) {
                        sql.append(" ORDER BY ").append(sortOrder);
                    }
                    
                    Log.d(TAG, "执行SQL查询: " + sql.toString());
                    if (selectionArgs != null) {
                        Log.d(TAG, "查询参数: " + java.util.Arrays.toString(selectionArgs));
                    }
                    
                    statement = connection.prepareStatement(sql.toString());
                    
                    if (selectionArgs != null) {
                        for (int i = 0; i < selectionArgs.length; i++) {
                            statement.setString(i + 1, selectionArgs[i]);
                        }
                    }
                    
                    resultSet = statement.executeQuery();
                    
                    // 创建MatrixCursor来模拟SQLite的Cursor
                    String[] columns = {COLUMN_ID, "relationship_id", "owner", USER_ID, COLUMN_TITLE, COLUMN_TYPE, COLUMN_AMOUNT, COLUMN_DATE, COLUMN_TIME, COLUMN_INCOME_TYPE};
                    MatrixCursor cursor = new MatrixCursor(columns);
                    
                    while (resultSet.next()) {
                        Object[] row = new Object[columns.length];
                        row[0] = resultSet.getInt("_id");
                        row[1] = resultSet.getInt("relationship_id");
                        row[2] = resultSet.getString("owner");
                        row[3] = resultSet.getInt("userId");
                        row[4] = resultSet.getString("title");
                        row[5] = resultSet.getString("type");
                        row[6] = resultSet.getDouble("amount");
                        row[7] = resultSet.getString("date");
                        row[8] = resultSet.getString("time");
                        row[9] = resultSet.getInt("income_type");
                        cursor.addRow(row);
                    }
                    
                    return cursor;
                    
                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "查询账单失败", e);
                    return null;
                } finally {
                    closeResources(connection, statement, resultSet);
                }
            }
            
            @Override
            protected void onPostExecute(Cursor cursor) {
                if (cursor != null) {
                    callback.onQuerySuccess(cursor);
                } else {
                    callback.onQueryError(errorMessage);
                }
            }
        }.execute();
    }
    
    // 插入账单数据
    public void insertBill(ContentValues values, BillInsertCallback callback) {
        new AsyncTask<Void, Void, Long>() {
            private String errorMessage = "";
            
            @Override
            protected Long doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                ResultSet resultSet = null;
                
                try {
                    connection = getConnection();
                    
                    String sql = "INSERT INTO bills (relationship_id, owner, user_id, title, type, amount, date, time, income_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
                    
                    // 设置参数，这里需要根据实际情况设置relationship_id和owner
                    statement.setInt(1, 1); // 默认relationship_id，需要根据实际情况获取
                    statement.setInt(2, 1); // 默认owner，改为整数类型
                    statement.setInt(3, values.getAsInteger(USER_ID));
                    statement.setString(4, values.getAsString(COLUMN_TITLE));
                    statement.setString(5, values.getAsString(COLUMN_TYPE));
                    statement.setDouble(6, values.getAsDouble(COLUMN_AMOUNT));
                    statement.setString(7, values.getAsString(COLUMN_DATE));
                    statement.setString(8, values.getAsString(COLUMN_TIME));
                    statement.setInt(9, values.getAsInteger(COLUMN_INCOME_TYPE));
                    
                    int rowsAffected = statement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        resultSet = statement.getGeneratedKeys();
                        if (resultSet.next()) {
                            return resultSet.getLong(1);
                        }
                    }
                    
                    return -1L;
                    
                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "插入账单失败", e);
                    return -1L;
                } finally {
                    closeResources(connection, statement, resultSet);
                }
            }
            
            @Override
            protected void onPostExecute(Long id) {
                if (id > 0) {
                    callback.onInsertSuccess(id);
                } else {
                    callback.onInsertError(errorMessage);
                }
            }
        }.execute();
    }
    
    // 删除账单数据
    public void deleteBill(String selection, String[] selectionArgs, BillDeleteCallback callback) {
        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";
            
            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                
                try {
                    connection = getConnection();
                    
                    StringBuilder sql = new StringBuilder("DELETE FROM bills");
                    
                    if (selection != null && !selection.isEmpty()) {
                        // 将SQLite字段名转换为MySQL字段名
                        String mysqlSelection = selection.replace(COLUMN_ID, "bill_id")
                                                        .replace(USER_ID, "user_id")
                                                        .replace(COLUMN_TYPE, "type")
                                                        .replace(COLUMN_AMOUNT, "amount")
                                                        .replace(COLUMN_DATE, "date")
                                                        .replace(COLUMN_TIME, "time")
                                                        .replace(COLUMN_INCOME_TYPE, "income_type");
                        sql.append(" WHERE ").append(mysqlSelection);
                    }
                    
                    Log.d(TAG, "执行删除SQL: " + sql.toString());
                    if (selectionArgs != null) {
                        Log.d(TAG, "删除参数: " + java.util.Arrays.toString(selectionArgs));
                    }
                    
                    statement = connection.prepareStatement(sql.toString());
                    
                    if (selectionArgs != null) {
                        for (int i = 0; i < selectionArgs.length; i++) {
                            statement.setString(i + 1, selectionArgs[i]);
                        }
                    }
                    
                    int rowsDeleted = statement.executeUpdate();
                    Log.d(TAG, "删除操作完成，影响行数: " + rowsDeleted);
                    return rowsDeleted;
                    
                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "删除账单失败", e);
                    return 0;
                } finally {
                    closeResources(connection, statement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Integer rowsDeleted) {
                if (rowsDeleted > 0) {
                    callback.onDeleteSuccess(rowsDeleted);
                } else {
                    callback.onDeleteError(errorMessage);
                }
            }
        }.execute();
    }
    
    // 更新账单数据
    public void updateBill(ContentValues values, String selection, String[] selectionArgs, BillUpdateCallback callback) {
        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";
            
            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                
                try {
                    connection = getConnection();
                    
                    StringBuilder sql = new StringBuilder("UPDATE bills SET ");
                    List<String> setParts = new ArrayList<>();
                    List<Object> params = new ArrayList<>();
                    
                    // 构建SET子句
                    if (values.containsKey(USER_ID)) {
                        setParts.add("user_id = ?");
                        params.add(values.getAsInteger(USER_ID));
                    }
                    if (values.containsKey(COLUMN_TITLE)) {
                        setParts.add("title = ?");
                        params.add(values.getAsString(COLUMN_TITLE));
                    }
                    if (values.containsKey(COLUMN_TYPE)) {
                        setParts.add("type = ?");
                        params.add(values.getAsString(COLUMN_TYPE));
                    }
                    if (values.containsKey(COLUMN_AMOUNT)) {
                        setParts.add("amount = ?");
                        params.add(values.getAsDouble(COLUMN_AMOUNT));
                    }
                    if (values.containsKey(COLUMN_DATE)) {
                        setParts.add("date = ?");
                        params.add(values.getAsString(COLUMN_DATE));
                    }
                    if (values.containsKey(COLUMN_TIME)) {
                        setParts.add("time = ?");
                        params.add(values.getAsString(COLUMN_TIME));
                    }
                    if (values.containsKey(COLUMN_INCOME_TYPE)) {
                        setParts.add("income_type = ?");
                        params.add(values.getAsInteger(COLUMN_INCOME_TYPE));
                    }
                    
                    sql.append(String.join(", ", setParts));
                    
                    if (selection != null && !selection.isEmpty()) {
                        String mysqlSelection = selection.replace(COLUMN_ID, "bill_id")
                                                        .replace(USER_ID, "user_id")
                                                        .replace(COLUMN_TYPE, "type")
                                                        .replace(COLUMN_AMOUNT, "amount")
                                                        .replace(COLUMN_DATE, "date")
                                                        .replace(COLUMN_TIME, "time")
                                                        .replace(COLUMN_INCOME_TYPE, "income_type");
                        sql.append(" WHERE ").append(mysqlSelection);
                    }
                    
                    statement = connection.prepareStatement(sql.toString());
                    
                    // 设置参数
                    int paramIndex = 1;
                    for (Object param : params) {
                        statement.setObject(paramIndex++, param);
                    }
                    
                    if (selectionArgs != null) {
                        for (String arg : selectionArgs) {
                            statement.setString(paramIndex++, arg);
                        }
                    }
                    
                    return statement.executeUpdate();
                    
                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "更新账单失败", e);
                    return 0;
                } finally {
                    closeResources(connection, statement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Integer rowsUpdated) {
                if (rowsUpdated > 0) {
                    callback.onUpdateSuccess(rowsUpdated);
                } else {
                    callback.onUpdateError(errorMessage);
                }
            }
        }.execute();
    }
    
    // 简化版查询方法，用于返回Map列表
    public void queryBills(String selection, QueryCallback callback) {
        // 处理包含%%或%的selection，转换为LIKE查询
        String processedSelection = null;
        String[] selectionArgs = null;
        
        Log.d(TAG, "简化版queryBills接收到selection: " + selection);
        
        if (selection != null && !selection.isEmpty() && (selection.contains("%%") || selection.matches(".*\\d{4}-\\d{2}-%.*"))) {
            // 处理日期模式查询，确保使用LIKE语法
            String pattern;
            if (selection.contains("%%")) {
                pattern = selection.replace("%%", "%");
            } else {
                pattern = selection;
            }
            processedSelection = "date LIKE ?";
            selectionArgs = new String[]{pattern};
            Log.d(TAG, "处理后的selection: " + processedSelection + ", args: " + java.util.Arrays.toString(selectionArgs));
        } else if (selection != null && !selection.isEmpty()) {
            processedSelection = selection;
            Log.d(TAG, "直接使用selection: " + processedSelection);
        }
        
        queryBills(processedSelection, selectionArgs, "date DESC, time DESC", new BillQueryCallback() {
            @Override
            public void onQuerySuccess(Cursor cursor) {
                List<Map<String, Object>> results = new ArrayList<>();
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        Map<String, Object> row = new HashMap<>();
                        row.put("_id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
                        row.put("amount", cursor.getDouble(cursor.getColumnIndexOrThrow("amount")));
                        row.put("date", cursor.getString(cursor.getColumnIndexOrThrow("date")));
                        row.put("time", cursor.getString(cursor.getColumnIndexOrThrow("time")));
                        row.put("userId", cursor.getInt(cursor.getColumnIndexOrThrow("userId")));
                        row.put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                        row.put("title", cursor.getString(cursor.getColumnIndexOrThrow("title")));
                        row.put("income_type", cursor.getInt(cursor.getColumnIndexOrThrow("income_type")));
                        results.add(row);
                    } while (cursor.moveToNext());
                    cursor.close();
                }
                callback.onSuccess(results);
            }

            @Override
            public void onQueryError(String error) {
                callback.onError(error);
            }
        });
    }

    // 回调接口
    public interface QueryCallback {
        void onSuccess(List<Map<String, Object>> results);
        void onError(String error);
    }
    
    public interface BillQueryCallback {
        void onQuerySuccess(Cursor cursor);
        void onQueryError(String error);
    }
    
    public interface BillInsertCallback {
        void onInsertSuccess(long id);
        void onInsertError(String error);
    }
    
    public interface BillDeleteCallback {
        void onDeleteSuccess(int rowsDeleted);
        void onDeleteError(String error);
    }
    
    public interface BillUpdateCallback {
        void onUpdateSuccess(int rowsUpdated);
        void onUpdateError(String error);
    }
}