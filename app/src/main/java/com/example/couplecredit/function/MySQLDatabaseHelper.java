package com.example.couplecredit.function;

import android.os.AsyncTask;
import android.util.Log;

import com.example.couplecredit.database.DatabaseConnectionPool;
import com.example.couplecredit.database.DatabaseInitializer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * MySQL远程数据库操作工具类
 * 用于连接远程MySQL服务器并进行用户注册信息的存储
 */
public class MySQLDatabaseHelper {
    
    private static final String TAG = "MySQLDatabaseHelper";
    
    public MySQLDatabaseHelper() {
        // 使用统一的连接池初始化工具
        DatabaseInitializer.initializeConnectionPoolAsync(TAG);
    }
    
    // 数据库连接配置
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "demodb";
    private static final String DB_USER = "demodb";
    private static final String DB_PASSWORD = "root";
    
    // JDBC连接URL
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    /**
     * 获取数据库连接（使用连接池）
     */
    private static Connection getConnection() throws SQLException, ClassNotFoundException {
        try {
            return DatabaseConnectionPool.getInstance().getConnection();
        } catch (SQLException e) {
            Log.w(TAG, "连接池获取连接失败，尝试直接连接: " + e.getMessage());
            // 降级到直接连接
            Class.forName("com.mysql.jdbc.Driver");
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
        }
    }
    
    /**
     * 关闭数据库资源（连接归还到连接池）
     */
    private static void closeResources(Connection connection, PreparedStatement statement, java.sql.ResultSet resultSet) {
        try {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
            if (connection != null) {
                // 将连接归还到连接池而不是关闭
                DatabaseConnectionPool.getInstance().returnConnection(connection);
            }
        } catch (SQLException e) {
            Log.e(TAG, "关闭数据库资源失败", e);
        }
    }
    

    
    /**
     * 插入用户注册信息
     */
    public static void insertUser(String username, String email, String password, DatabaseCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String insertSQL = "INSERT INTO users (username, email, password, status) VALUES (?, ?, ?, ?)";
                    preparedStatement = connection.prepareStatement(insertSQL);
                    preparedStatement.setString(1, username);
                    preparedStatement.setString(2, email);
                    preparedStatement.setString(3, password);
                    preparedStatement.setString(4, "active"); // 设置默认状态为active
                    
                    int rowsAffected = preparedStatement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        // 注册成功
                        return true;
                    } else {
                        errorMessage = "插入失败，没有行被影响";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) { // MySQL duplicate entry error
                        if (e.getMessage().contains("username")) {
                            errorMessage = "用户名已存在，请选择其他用户名";
                        } else if (e.getMessage().contains("email")) {
                            errorMessage = "邮箱已被注册，请使用其他邮箱";
                        } else {
                            errorMessage = "用户名或邮箱已存在";
                        }
                    } else {
                        errorMessage = "数据库错误: " + e.getMessage();
                    }
                    Log.e(TAG, "插入用户信息失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return false;
                } finally {
                    closeResources(connection, preparedStatement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess("用户注册成功");
                    } else {
                        callback.onError("注册失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 用户登录验证
     */
    public static void loginUser(String username, String password, LoginCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            private String userInfo = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String selectSQL = "SELECT id, username, email FROM users WHERE username = ? AND password = ? AND status = 'active'";
                    preparedStatement = connection.prepareStatement(selectSQL);
                    preparedStatement.setString(1, username);
                    preparedStatement.setString(2, password);
                    
                    java.sql.ResultSet resultSet = preparedStatement.executeQuery();
                    
                    if (resultSet.next()) {
                        // 返回用户ID而不是用户名
                        userInfo = String.valueOf(resultSet.getInt("id"));
                        // 登录成功
                        return true;
                    } else {
                        errorMessage = "用户名或密码错误";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "登录验证失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return false;
                } finally {
                    closeResources(connection, preparedStatement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onLoginSuccess("登录成功", userInfo);
                    } else {
                        callback.onLoginError("登录失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 删除用户账户
     */
    public static void deleteUser(String username, DatabaseCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String deleteSQL = "DELETE FROM users WHERE username = ?";
                    preparedStatement = connection.prepareStatement(deleteSQL);
                    preparedStatement.setString(1, username);
                    
                    int rowsAffected = preparedStatement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        // 删除成功
                        return true;
                    } else {
                        errorMessage = "用户不存在或删除失败";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "删除用户失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return false;
                } finally {
                    closeResources(connection, preparedStatement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess("用户账户删除成功");
                    } else {
                        callback.onError("删除失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 修改用户密码
     */
    public static void updatePassword(String username, String oldPassword, String newPassword, DatabaseCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement verifyStatement = null;
                PreparedStatement updateStatement = null;
                
                try {
                    connection = getConnection();
                    
                    // 首先验证当前密码
                    String verifySQL = "SELECT COUNT(*) FROM users WHERE username = ? AND password = ?";
                    verifyStatement = connection.prepareStatement(verifySQL);
                    verifyStatement.setString(1, username);
                    verifyStatement.setString(2, oldPassword);
                    
                    java.sql.ResultSet resultSet = verifyStatement.executeQuery();
                    if (resultSet.next() && resultSet.getInt(1) == 0) {
                        errorMessage = "当前密码不正确";
                        return false;
                    }
                    
                    // 更新密码
                    String updateSQL = "UPDATE users SET password = ? WHERE username = ?";
                    updateStatement = connection.prepareStatement(updateSQL);
                    updateStatement.setString(1, newPassword);
                    updateStatement.setString(2, username);
                    
                    int rowsAffected = updateStatement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        // 密码修改成功
                        return true;
                    } else {
                        errorMessage = "密码修改失败";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库操作失败: " + e.getMessage();
                    Log.e(TAG, "修改密码失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return false;
                } finally {
                    try {
                        if (verifyStatement != null) verifyStatement.close();
                        if (updateStatement != null) updateStatement.close();
                        if (connection != null) connection.close();
                    } catch (SQLException e) {
                        Log.e(TAG, "关闭数据库连接失败: " + e.getMessage());
                    }
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess("密码修改成功");
                    } else {
                        callback.onError(errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 更新用户昵称
     */
    public static void updateUserNickname(String username, String newNickname, DatabaseCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String updateSQL = "UPDATE users SET nickname = ? WHERE username = ? AND status = 'active'";
                    preparedStatement = connection.prepareStatement(updateSQL);
                    preparedStatement.setString(1, newNickname);
                    preparedStatement.setString(2, username);
                    
                    int rowsAffected = preparedStatement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        // 更新成功
                        return true;
                    } else {
                        errorMessage = "用户不存在或更新失败";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "更新用户昵称失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return false;
                } finally {
                    closeResources(connection, preparedStatement, null);
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess("昵称更新成功");
                    } else {
                        callback.onError("更新失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }

    /**
     * 获取用户昵称
     */
    public static void getUserNickname(String username, UserNicknameCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String errorMessage = "";
            
            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String selectSQL = "SELECT nickname FROM users WHERE username = ? AND status = 'active'";
                    preparedStatement = connection.prepareStatement(selectSQL);
                    preparedStatement.setString(1, username);
                    
                    java.sql.ResultSet resultSet = preparedStatement.executeQuery();
                    
                    if (resultSet.next()) {
                        String nickname = resultSet.getString("nickname");
                        return nickname; // 可能为null
                    } else {
                        errorMessage = "用户不存在";
                        return null;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "查询用户昵称失败: " + e.getMessage(), e);
                    return null;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return null;
                } finally {
                    closeResources(connection, preparedStatement, null);
                }
            }
            
            @Override
            protected void onPostExecute(String nickname) {
                if (callback != null) {
                    if (errorMessage.isEmpty()) {
                        callback.onSuccess(nickname);
                    } else {
                        callback.onError("查询失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }

    /**
     * 回调接口
     */
    public interface DatabaseCallback {
        void onSuccess(String message);
        void onError(String error);
    }
    
    /**
     * 通过用户名查询用户ID
     */
    public static void getUserIdByUsername(String username, UserIdCallback callback) {
        new AsyncTask<Void, Void, Integer>() {
            private String errorMessage = "";
            
            @Override
            protected Integer doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String selectSQL = "SELECT id FROM users WHERE username = ? AND status = 'active'";
                    preparedStatement = connection.prepareStatement(selectSQL);
                    preparedStatement.setString(1, username);
                    
                    java.sql.ResultSet resultSet = preparedStatement.executeQuery();
                    
                    if (resultSet.next()) {
                        int userId = resultSet.getInt("id");
                        // 查询用户ID成功
                        return userId;
                    } else {
                        errorMessage = "用户不存在";
                        return -1;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "查询用户ID失败: " + e.getMessage(), e);
                    return -1;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return -1;
                } finally {
                    try {
                        if (preparedStatement != null) preparedStatement.close();
                        if (connection != null) connection.close();
                    } catch (SQLException e) {
                        Log.e(TAG, "关闭数据库连接失败: " + e.getMessage());
                    }
                }
            }
            
            @Override
            protected void onPostExecute(Integer userId) {
                if (callback != null) {
                    if (userId != -1) {
                        callback.onSuccess(userId);
                    } else {
                        callback.onError("查询失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }

    /**
     * 登录回调接口
     */
    public interface LoginCallback {
        void onLoginSuccess(String message, String userInfo);
        void onLoginError(String error);
    }
    
    /**
     * 用户ID查询回调接口
     */
    public interface UserIdCallback {
        void onSuccess(int userId);
        void onError(String error);
    }
    
    /**
     * 根据用户ID获取用户昵称
     */
    public static void getUserNicknameById(int userId, UserNicknameCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String errorMessage = "";
            
            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    connection = getConnection();
                    
                    String selectSQL = "SELECT nickname, username FROM users WHERE id = ? AND status = 'active'";
                    preparedStatement = connection.prepareStatement(selectSQL);
                    preparedStatement.setInt(1, userId);
                    
                    java.sql.ResultSet resultSet = preparedStatement.executeQuery();
                    
                    if (resultSet.next()) {
                        String nickname = resultSet.getString("nickname");
                        String username = resultSet.getString("username");
                        // 如果昵称为空或null，返回用户名
                        return (nickname != null && !nickname.trim().isEmpty()) ? nickname : username;
                    } else {
                        errorMessage = "用户不存在";
                        return null;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "查询用户昵称失败: " + e.getMessage(), e);
                    return null;
                } catch (Exception e) {
                    errorMessage = "连接错误: " + e.getMessage();
                    Log.e(TAG, "数据库连接失败: " + e.getMessage(), e);
                    return null;
                } finally {
                    try {
                        if (preparedStatement != null) preparedStatement.close();
                        if (connection != null) connection.close();
                    } catch (SQLException e) {
                        Log.e(TAG, "关闭数据库连接失败: " + e.getMessage());
                    }
                }
            }
            
            @Override
            protected void onPostExecute(String displayName) {
                if (callback != null) {
                    if (errorMessage.isEmpty()) {
                        callback.onSuccess(displayName);
                    } else {
                        callback.onError("查询失败: " + errorMessage);
                    }
                }
            }
        }.execute();
    }

    /**
     * 用户昵称查询回调接口
     */
    public interface UserNicknameCallback {
        void onSuccess(String nickname); // nickname可能为null
        void onError(String error);
    }
}