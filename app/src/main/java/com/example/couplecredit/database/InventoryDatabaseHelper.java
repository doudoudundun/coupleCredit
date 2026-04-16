package com.example.couplecredit.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.AsyncTask;
import android.util.Log;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.example.couplecredit.utils.DatabaseResourceManager;
import com.example.couplecredit.utils.DatabaseExceptionHandler;
import com.example.couplecredit.config.DatabaseConfig;

public class InventoryDatabaseHelper {
    private static final String TAG = "InventoryDatabaseHelper";

    // 表名和字段常量
    public static final String TABLE_INVENTORY = "inventory";
    public static final String COLUMN_ID = "inventory_id";
    public static final String COLUMN_USER_ID = "user_id";
    public static final String COLUMN_RELATIONSHIP_ID = "relationship_id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_CATEGORY = "category";
    public static final String COLUMN_IMAGE_URL = "image_url";
    public static final String COLUMN_QUANTITY = "quantity";
    public static final String COLUMN_UNIT = "unit";
    public static final String COLUMN_THRESHOLD = "threshold";
    public static final String COLUMN_CREATED_AT = "created_at";
    public static final String COLUMN_UPDATED_AT = "updated_at";
    public static final String COLUMN_LAST_CONSUMED_AT = "last_consumed_at";
    public static final String COLUMN_NOTE = "note";
    public static final String COLUMN_AI_IMAGE_PROMPT = "ai_image_prompt";

    private Context context;
    private static final long CACHE_EXPIRY_MS = 60000; // 60秒缓存
    private static final String CACHE_PREFIX = "inventory_";

    public InventoryDatabaseHelper(Context context) {
        this.context = context;
        DatabaseInitializer.initializeConnectionPoolAsync(TAG);
    }

    private Connection getConnection() throws ClassNotFoundException, SQLException {
        if (!DatabaseInitializer.hasDatabaseConfig()) {
            throw new SQLException("无数据库配置，请使用 HTTP API 模式");
        }
        return DatabaseConnectionPool.getInstance().getConnection();
    }

    private void closeResources(Connection connection, PreparedStatement statement, ResultSet resultSet) {
        DatabaseResourceManager.closeResources(connection, statement, resultSet);
    }

    private String handleException(Exception e) {
        return DatabaseExceptionHandler.handleException(e);
    }

    public void queryInventory(int userId, Integer relationshipId, InventoryQueryCallback callback) {
        String cacheKey = CACHE_PREFIX + userId + "_" + (relationshipId != null ? relationshipId : "null");

        QueryCacheManager.CacheEntry cached = QueryCacheManager.get(cacheKey);
        if (cached != null && !cached.isExpired(CACHE_EXPIRY_MS) && cached.data instanceof Cursor) {
            callback.onQuerySuccess((Cursor) cached.data);
            return;
        }

        new AsyncTask<Void, Void, Cursor>() {
            private String errorMessage = "";

            @Override
            protected Cursor doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                ResultSet resultSet = null;

                try {
                    connection = getConnection();

                    String sql;
                    if (relationshipId != null) {
                        sql = "SELECT inventory_id, user_id, relationship_id, name, category, image_url, " +
                              "quantity, unit, threshold, created_at, updated_at, last_consumed_at, note, ai_image_prompt " +
                              "FROM inventory WHERE relationship_id = ? ORDER BY updated_at DESC";
                        statement = connection.prepareStatement(sql);
                        statement.setInt(1, relationshipId);
                    } else {
                        sql = "SELECT inventory_id, user_id, relationship_id, name, category, image_url, " +
                              "quantity, unit, threshold, created_at, updated_at, last_consumed_at, note, ai_image_prompt " +
                              "FROM inventory WHERE user_id = ? AND relationship_id IS NULL ORDER BY updated_at DESC";
                        statement = connection.prepareStatement(sql);
                        statement.setInt(1, userId);
                    }

                    resultSet = statement.executeQuery();

                    String[] columns = {COLUMN_ID, COLUMN_USER_ID, COLUMN_RELATIONSHIP_ID, COLUMN_NAME,
                            COLUMN_CATEGORY, COLUMN_IMAGE_URL, COLUMN_QUANTITY, COLUMN_UNIT,
                            COLUMN_THRESHOLD, COLUMN_CREATED_AT, COLUMN_UPDATED_AT,
                            COLUMN_LAST_CONSUMED_AT, COLUMN_NOTE, COLUMN_AI_IMAGE_PROMPT};
                    MatrixCursor cursor = new MatrixCursor(columns);

                    while (resultSet.next()) {
                        Object[] row = new Object[columns.length];
                        row[0] = resultSet.getInt("inventory_id");
                        row[1] = resultSet.getInt("user_id");
                        row[2] = resultSet.getObject("relationship_id");
                        row[3] = resultSet.getString("name");
                        row[4] = resultSet.getString("category");
                        row[5] = resultSet.getString("image_url");
                        row[6] = resultSet.getDouble("quantity");
                        row[7] = resultSet.getString("unit");
                        row[8] = resultSet.getDouble("threshold");
                        row[9] = resultSet.getString("created_at");
                        row[10] = resultSet.getString("updated_at");
                        row[11] = resultSet.getString("last_consumed_at");
                        row[12] = resultSet.getString("note");
                        row[13] = resultSet.getString("ai_image_prompt");
                        cursor.addRow(row);
                    }

                    return cursor;

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "查询存货失败", e);
                    return null;
                } finally {
                    closeResources(connection, statement, resultSet);
                }
            }

            @Override
            protected void onPostExecute(Cursor cursor) {
                if (cursor != null) {
                    QueryCacheManager.put(cacheKey, new QueryCacheManager.CacheEntry(cursor));
                    callback.onQuerySuccess(cursor);
                } else {
                    callback.onQueryError(errorMessage);
                }
            }
        }.execute();
    }

    public void insertInventory(ContentValues values, InventoryInsertCallback callback) {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);

        new AsyncTask<Void, Void, Long>() {
            private String errorMessage = "";

            @Override
            protected Long doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                ResultSet resultSet = null;

                try {
                    connection = getConnection();

                    String sql = "INSERT INTO inventory (user_id, relationship_id, name, category, image_url, " +
                                 "quantity, unit, threshold, note, ai_image_prompt, created_at, updated_at) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
                    statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);

                    Integer userId = values.getAsInteger(COLUMN_USER_ID);
                    Integer relationshipId = values.getAsInteger(COLUMN_RELATIONSHIP_ID);
                    statement.setInt(1, userId);

                    if (relationshipId != null) {
                        statement.setInt(2, relationshipId);
                    } else {
                        statement.setNull(2, java.sql.Types.INTEGER);
                    }

                    statement.setString(3, values.getAsString(COLUMN_NAME));
                    statement.setString(4, values.getAsString(COLUMN_CATEGORY));
                    statement.setString(5, values.getAsString(COLUMN_IMAGE_URL));
                    statement.setDouble(6, values.getAsDouble(COLUMN_QUANTITY));
                    statement.setString(7, values.getAsString(COLUMN_UNIT));
                    statement.setDouble(8, values.getAsDouble(COLUMN_THRESHOLD));
                    statement.setString(9, values.getAsString(COLUMN_NOTE));
                    statement.setString(10, values.getAsString(COLUMN_AI_IMAGE_PROMPT));

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
                    Log.e(TAG, "添加存货失败", e);
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

    public void consumeInventory(int inventoryId, double consumeAmount, InventoryUpdateCallback callback) {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);

        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";

            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;

                try {
                    connection = getConnection();

                    String sql = "UPDATE inventory SET quantity = quantity - ?, last_consumed_at = NOW(), " +
                                 "updated_at = NOW() WHERE inventory_id = ? AND quantity >= ?";
                    statement = connection.prepareStatement(sql);
                    statement.setDouble(1, consumeAmount);
                    statement.setInt(2, inventoryId);
                    statement.setDouble(3, consumeAmount);

                    return statement.executeUpdate();

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "消耗存货失败", e);
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
                    callback.onUpdateError(errorMessage != null && !errorMessage.isEmpty() ?
                            errorMessage : "存货不足或不存在");
                }
            }
        }.execute();
    }

    public void replenishInventory(int inventoryId, double addAmount, InventoryUpdateCallback callback) {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);

        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";

            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;

                try {
                    connection = getConnection();

                    String sql = "UPDATE inventory SET quantity = quantity + ?, updated_at = NOW() " +
                                 "WHERE inventory_id = ?";
                    statement = connection.prepareStatement(sql);
                    statement.setDouble(1, addAmount);
                    statement.setInt(2, inventoryId);

                    return statement.executeUpdate();

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "补货失败", e);
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

    public void updateInventory(ContentValues values, int inventoryId, InventoryUpdateCallback callback) {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);

        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";

            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;

                try {
                    connection = getConnection();

                    StringBuilder sql = new StringBuilder("UPDATE inventory SET updated_at = NOW()");
                    List<String> setParts = new ArrayList<>();
                    List<Object> params = new ArrayList<>();

                    if (values.containsKey(COLUMN_NAME)) {
                        setParts.add("name = ?");
                        params.add(values.getAsString(COLUMN_NAME));
                    }
                    if (values.containsKey(COLUMN_CATEGORY)) {
                        setParts.add("category = ?");
                        params.add(values.getAsString(COLUMN_CATEGORY));
                    }
                    if (values.containsKey(COLUMN_IMAGE_URL)) {
                        setParts.add("image_url = ?");
                        params.add(values.getAsString(COLUMN_IMAGE_URL));
                    }
                    if (values.containsKey(COLUMN_QUANTITY)) {
                        setParts.add("quantity = ?");
                        params.add(values.getAsDouble(COLUMN_QUANTITY));
                    }
                    if (values.containsKey(COLUMN_UNIT)) {
                        setParts.add("unit = ?");
                        params.add(values.getAsString(COLUMN_UNIT));
                    }
                    if (values.containsKey(COLUMN_THRESHOLD)) {
                        setParts.add("threshold = ?");
                        params.add(values.getAsDouble(COLUMN_THRESHOLD));
                    }
                    if (values.containsKey(COLUMN_NOTE)) {
                        setParts.add("note = ?");
                        params.add(values.getAsString(COLUMN_NOTE));
                    }

                    if (setParts.isEmpty()) {
                        return 0;
                    }

                    sql.append(", ").append(String.join(", ", setParts));
                    sql.append(" WHERE inventory_id = ?");

                    statement = connection.prepareStatement(sql.toString());

                    int paramIndex = 1;
                    for (Object param : params) {
                        statement.setObject(paramIndex++, param);
                    }
                    statement.setInt(paramIndex, inventoryId);

                    return statement.executeUpdate();

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "更新存货失败", e);
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

    public void deleteInventory(int inventoryId, InventoryDeleteCallback callback) {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);

        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";

            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;

                try {
                    connection = getConnection();

                    String sql = "DELETE FROM inventory WHERE inventory_id = ?";
                    statement = connection.prepareStatement(sql);
                    statement.setInt(1, inventoryId);

                    return statement.executeUpdate();

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "删除存货失败", e);
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

    public void queryLowStockInventory(int userId, Integer relationshipId, InventoryQueryCallback callback) {
        new AsyncTask<Void, Void, Cursor>() {
            private String errorMessage = "";

            @Override
            protected Cursor doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement statement = null;
                ResultSet resultSet = null;

                try {
                    connection = getConnection();

                    String sql;
                    if (relationshipId != null) {
                        sql = "SELECT inventory_id, user_id, relationship_id, name, category, image_url, " +
                              "quantity, unit, threshold, created_at, updated_at, last_consumed_at, note, ai_image_prompt " +
                              "FROM inventory WHERE relationship_id = ? AND quantity <= threshold ORDER BY quantity ASC";
                        statement = connection.prepareStatement(sql);
                        statement.setInt(1, relationshipId);
                    } else {
                        sql = "SELECT inventory_id, user_id, relationship_id, name, category, image_url, " +
                              "quantity, unit, threshold, created_at, updated_at, last_consumed_at, note, ai_image_prompt " +
                              "FROM inventory WHERE user_id = ? AND relationship_id IS NULL AND quantity <= threshold ORDER BY quantity ASC";
                        statement = connection.prepareStatement(sql);
                        statement.setInt(1, userId);
                    }

                    resultSet = statement.executeQuery();

                    String[] columns = {COLUMN_ID, COLUMN_USER_ID, COLUMN_RELATIONSHIP_ID, COLUMN_NAME,
                            COLUMN_CATEGORY, COLUMN_IMAGE_URL, COLUMN_QUANTITY, COLUMN_UNIT,
                            COLUMN_THRESHOLD, COLUMN_CREATED_AT, COLUMN_UPDATED_AT,
                            COLUMN_LAST_CONSUMED_AT, COLUMN_NOTE, COLUMN_AI_IMAGE_PROMPT};
                    MatrixCursor cursor = new MatrixCursor(columns);

                    while (resultSet.next()) {
                        Object[] row = new Object[columns.length];
                        row[0] = resultSet.getInt("inventory_id");
                        row[1] = resultSet.getInt("user_id");
                        row[2] = resultSet.getObject("relationship_id");
                        row[3] = resultSet.getString("name");
                        row[4] = resultSet.getString("category");
                        row[5] = resultSet.getString("image_url");
                        row[6] = resultSet.getDouble("quantity");
                        row[7] = resultSet.getString("unit");
                        row[8] = resultSet.getDouble("threshold");
                        row[9] = resultSet.getString("created_at");
                        row[10] = resultSet.getString("updated_at");
                        row[11] = resultSet.getString("last_consumed_at");
                        row[12] = resultSet.getString("note");
                        row[13] = resultSet.getString("ai_image_prompt");
                        cursor.addRow(row);
                    }

                    return cursor;

                } catch (Exception e) {
                    errorMessage = handleException(e);
                    Log.e(TAG, "查询告急存货失败", e);
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

    public static void clearCache() {
        QueryCacheManager.clearPrefix(CACHE_PREFIX);
        Log.d(TAG, "存货查询缓存已清空");
    }

    // 回调接口
    public interface InventoryQueryCallback {
        void onQuerySuccess(Cursor cursor);
        void onQueryError(String error);
    }

    public interface InventoryInsertCallback {
        void onInsertSuccess(long id);
        void onInsertError(String error);
    }

    public interface InventoryUpdateCallback {
        void onUpdateSuccess(int rowsUpdated);
        void onUpdateError(String error);
    }

    public interface InventoryDeleteCallback {
        void onDeleteSuccess(int rowsDeleted);
        void onDeleteError(String error);
    }
}