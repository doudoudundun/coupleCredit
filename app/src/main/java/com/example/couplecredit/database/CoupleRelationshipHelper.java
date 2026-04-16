package com.example.couplecredit.database;

import android.os.AsyncTask;
import android.util.Log;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;
import com.example.couplecredit.utils.DatabaseResourceManager;
import com.example.couplecredit.utils.DatabaseExceptionHandler;
import com.example.couplecredit.config.DatabaseConfig;

public class CoupleRelationshipHelper {
    private static final String TAG = "CoupleRelationshipHelper";
    // 使用统一的数据库配置

    public CoupleRelationshipHelper() {
        // 使用统一的连接池初始化工具（异步，会自动检查配置）
        DatabaseInitializer.initializeConnectionPoolAsync(TAG);
    }

    // 获取数据库连接（使用连接池）
    private Connection getConnection() throws ClassNotFoundException, SQLException {
        // 检查是否有数据库配置
        if (!DatabaseInitializer.hasDatabaseConfig()) {
            throw new SQLException("无数据库配置，请使用 HTTP API 模式");
        }

        try {
            return DatabaseConnectionPool.getInstance().getConnection();
        } catch (SQLException e) {
            Log.w(TAG, "连接池获取连接失败，尝试直接连接: " + e.getMessage());
            // 降级到直接连接
            Class.forName("com.mysql.jdbc.Driver");
            return DriverManager.getConnection(DatabaseConfig.DB_URL, DatabaseConfig.DB_USER, DatabaseConfig.DB_PASSWORD);
        }
    }

    // 关闭数据库资源（使用统一的资源管理器）
    private void closeResources(Connection connection, PreparedStatement... statements) {
        for (PreparedStatement stmt : statements) {
            DatabaseResourceManager.closeStatement(stmt);
        }
        DatabaseResourceManager.closeConnection(connection);
    }

    private void closeResources(Connection connection, ResultSet resultSet, PreparedStatement... statements) {
        DatabaseResourceManager.closeResultSet(resultSet);
        for (PreparedStatement stmt : statements) {
            DatabaseResourceManager.closeStatement(stmt);
        }
        DatabaseResourceManager.closeConnection(connection);
    }

    // 处理数据库异常（使用统一的异常处理器）
    private String handleException(Exception e) {
        return DatabaseExceptionHandler.handleException(e);
    }


    public interface CoupleCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface InviteCodeCallback {
        void onInviteCodeGenerated(String inviteCode);
        void onError(String error);
    }

    public interface UserSearchCallback {
        void onUserFound(int userId, String username);
        void onUserNotFound();
        void onError(String error);
    }

    public interface CoupleInfoCallback {
        void onCoupleFound(int coupleId, String coupleName, String coupleNickname);
        void onNoCoupleFound();
        void onError(String error);
    }

    public interface UnbindCallback {
        void onSuccess();
        void onError(String error);
    }
    
    public interface RelationshipIdCallback {
        void onRelationshipIdFound(int relationshipId);
        void onNoRelationshipFound();
        void onError(String error);
    }
    
    public interface UserRoleCallback {
        void onRoleFound(int ownerId); // 1=邀请者, 2=被邀请者
        void onNoRelationshipFound();
        void onError(String error);
    }

    // 获取情侣信息
    public void getCoupleInfo(int userId, CoupleInfoCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int coupleId = -1;
            private String coupleName = null;
            private String coupleNickname = null;

            @Override
            protected Void doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();

                    // 查询当前用户的情侣关系，包含昵称信息
                    String sql = "SELECT cr.user_id_1, cr.user_id_2, u1.username as name1, u2.username as name2, " +
                               "u1.nickname as nickname1, u2.nickname as nickname2 " +
                               "FROM couple_relationships cr " +
                               "JOIN users u1 ON cr.user_id_1 = u1.id " +
                               "JOIN users u2 ON cr.user_id_2 = u2.id " +
                               "WHERE (cr.user_id_1 = ? OR cr.user_id_2 = ?) AND cr.status = 'active'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setInt(1, userId);
                    stmt.setInt(2, userId);
                    rs = stmt.executeQuery();

                    if (rs.next()) {
                        int user1Id = rs.getInt("user_id_1");
                        int user2Id = rs.getInt("user_id_2");
                        String name1 = rs.getString("name1");
                        String name2 = rs.getString("name2");
                        String nickname1 = rs.getString("nickname1");
                        String nickname2 = rs.getString("nickname2");
                        
                        // 确定情侣的ID和姓名（排除当前用户）
                        if (user1Id == userId) {
                            coupleId = user2Id;
                            coupleName = name2;
                            coupleNickname = nickname2;
                        } else {
                            coupleId = user1Id;
                            coupleName = name1;
                            coupleNickname = nickname1;
                        }
                    }

                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, stmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (error != null) {
                    callback.onError(error);
                } else if (coupleId != -1) {
                    callback.onCoupleFound(coupleId, coupleName, coupleNickname);
                } else {
                    callback.onNoCoupleFound();
                }
            }
        }.execute();
    }
    
    // 获取用户的relationship_id
    public void getUserRelationshipId(int userId, RelationshipIdCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int relationshipId = -1;
            private boolean hasRelationship = false;

            @Override
            protected Void doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;
                
                try {
                    connection = getConnection();
                    
                    // 查询用户的relationship_id
                    String sql = "SELECT cr.relationship_id " +
                               "FROM couple_relationships cr " +
                               "WHERE (cr.user_id_1 = ? OR cr.user_id_2 = ?) AND cr.status = 'active'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setInt(1, userId);
                    stmt.setInt(2, userId);
                    rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        relationshipId = rs.getInt("relationship_id");
                        hasRelationship = true;
                    }
                    
                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, stmt);
                }
                
                return null;
             }
             
             @Override
             protected void onPostExecute(Void result) {
                 executeOptimizedCallback(callback, error, hasRelationship, relationshipId);
             }
         }.execute();
     }
    
    // 优化版本的onPostExecute方法
    private void executeOptimizedCallback(RelationshipIdCallback callback, String error, boolean hasRelationship, int relationshipId) {
        if (error != null) {
            callback.onError(error);
        } else if (hasRelationship) {
            callback.onRelationshipIdFound(relationshipId);
        } else {
            callback.onNoRelationshipFound();
        }
    }
    
    // 优化版本：使用连接池直接获取relationship_id
    public void getUserRelationshipIdOptimized(int userId, RelationshipIdCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int relationshipId = -1;
            private boolean hasRelationship = false;

            @Override
            protected Void doInBackground(Void... voids) {
                long startTime = System.currentTimeMillis();
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;
                
                try {
                    // 使用连接池获取连接
                     connection = DatabaseConnectionPool.getInstance().getConnection();
                    long connectionTime = System.currentTimeMillis();
                    Log.d(TAG, "获取连接耗时: " + (connectionTime - startTime) + "ms");
                    
                    // 查询用户的relationship_id
                    String sql = "SELECT cr.relationship_id " +
                               "FROM couple_relationships cr " +
                               "WHERE (cr.user_id_1 = ? OR cr.user_id_2 = ?) AND cr.status = 'active'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setInt(1, userId);
                    stmt.setInt(2, userId);
                    
                    long queryStartTime = System.currentTimeMillis();
                    rs = stmt.executeQuery();
                    long queryTime = System.currentTimeMillis();
                    Log.d(TAG, "关系查询耗时: " + (queryTime - queryStartTime) + "ms");
                    
                    if (rs.next()) {
                        relationshipId = rs.getInt("relationship_id");
                        hasRelationship = true;
                    }
                    
                    long totalTime = System.currentTimeMillis();
                    Log.d(TAG, "关系查询总耗时: " + (totalTime - startTime) + "ms, 结果: " + (hasRelationship ? relationshipId : "无关系"));
                    
                } catch (Exception e) {
                    error = handleException(e);
                    Log.e(TAG, "关系查询失败: " + error);
                } finally {
                    if (rs != null) try { rs.close(); } catch (SQLException ignored) {}
                    if (stmt != null) try { stmt.close(); } catch (SQLException ignored) {}
                    if (connection != null) {
                        try {
                             DatabaseConnectionPool.getInstance().returnConnection(connection);
                         } catch (Exception e) {
                             Log.e(TAG, "释放连接失败: " + e.getMessage());
                         }
                    }
                }
                
                return null;
            }
            
            @Override
            protected void onPostExecute(Void result) {
                if (error != null) {
                    callback.onError(error);
                } else if (hasRelationship) {
                    callback.onRelationshipIdFound(relationshipId);
                } else {
                    callback.onNoRelationshipFound();
                }
            }
        }.execute();
    }
    
    // 获取用户在情侣关系中的角色
    public void getUserRole(int userId, UserRoleCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int ownerId = -1;
            private boolean hasRelationship = false;

            @Override
            protected Void doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;
                
                try {
                    connection = getConnection();
                    
                    // 查询用户在couple_relationships表中的角色
                    String sql = "SELECT user_id_1, user_id_2 " +
                               "FROM couple_relationships " +
                               "WHERE (user_id_1 = ? OR user_id_2 = ?) AND status = 'active'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setInt(1, userId);
                    stmt.setInt(2, userId);
                    rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        int user1Id = rs.getInt("user_id_1");
                        int user2Id = rs.getInt("user_id_2");
                        
                        // 判断用户角色：user_id_1是邀请者(ownerId=1)，user_id_2是被邀请者(ownerId=2)
                        if (user1Id == userId) {
                            ownerId = 1; // 邀请者
                        } else if (user2Id == userId) {
                            ownerId = 2; // 被邀请者
                        }
                        hasRelationship = true;
                    }
                    
                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, stmt);
                }
                
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (error != null) {
                    callback.onError(error);
                } else if (hasRelationship) {
                    callback.onRoleFound(ownerId);
                } else {
                    callback.onNoRelationshipFound();
                }
            }
        }.execute();
    }

    // 解绑情侣关系
    public void unbindCouple(int userId, UnbindCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String error = null;

            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement updateUsersStmt = null;
                PreparedStatement updateRelationshipStmt = null;
                PreparedStatement selectStmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();
                    connection.setAutoCommit(false); // 开启事务

                    // 查找当前用户的情侣关系
                    String selectSql = "SELECT user_id_1, user_id_2 FROM couple_relationships " +
                                     "WHERE (user_id_1 = ? OR user_id_2 = ?) AND status = 'active'";
                    selectStmt = connection.prepareStatement(selectSql);
                    selectStmt.setInt(1, userId);
                    selectStmt.setInt(2, userId);
                    rs = selectStmt.executeQuery();

                    if (!rs.next()) {
                        error = "未找到有效的情侣关系";
                        return null;
                    }

                    int user1Id = rs.getInt("user_id_1");
                    int user2Id = rs.getInt("user_id_2");
                    rs.close();
                    selectStmt.close();

                    // 更新情侣关系状态为dissolved
                    String updateRelationshipSql = "UPDATE couple_relationships SET status = 'dissolved' " +
                                                  "WHERE (user_id_1 = ? OR user_id_2 = ?) AND status = 'active'";
                    updateRelationshipStmt = connection.prepareStatement(updateRelationshipSql);
                    updateRelationshipStmt.setInt(1, userId);
                    updateRelationshipStmt.setInt(2, userId);
                    updateRelationshipStmt.executeUpdate();

                    // 更新两个用户的状态
                    String updateUsersSql = "UPDATE users SET couple_status = 'single', relationship_id = NULL " +
                                          "WHERE id IN (?, ?)";
                    updateUsersStmt = connection.prepareStatement(updateUsersSql);
                    updateUsersStmt.setInt(1, user1Id);
                    updateUsersStmt.setInt(2, user2Id);
                    int rowsUpdated = updateUsersStmt.executeUpdate();

                    if (rowsUpdated != 2) {
                        error = "更新用户状态失败";
                        connection.rollback();
                        return null;
                    }

                    connection.commit(); // 提交事务
                    return "情侣关系解绑成功";

                } catch (SQLException e) {
                    error = handleException(e);
                    try {
                        if (connection != null) connection.rollback();
                    } catch (SQLException rollbackEx) {
                        Log.e(TAG, "事务回滚失败", rollbackEx);
                    }
                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, selectStmt, updateUsersStmt, updateRelationshipStmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (error != null) {
                    callback.onError(error);
                } else {
                    callback.onSuccess();
                }
            }
        }.execute();
    }

    // 生成6位随机邀请码
    private String generateInviteCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    // 生成邀请码并更新用户状态为pending
    public void generateInviteCode(int userId, InviteCodeCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String error = null;
            private String inviteCode = null;

            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement checkStmt = null;
                PreparedStatement updateStmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();

                    // 检查用户当前状态
                    String checkSql = "SELECT couple_status FROM users WHERE id = ?";
                    checkStmt = connection.prepareStatement(checkSql);
                    checkStmt.setInt(1, userId);
                    rs = checkStmt.executeQuery();

                    if (rs.next()) {
                        String currentStatus = rs.getString("couple_status");
                        if ("coupled".equals(currentStatus)) {
                            error = "您已经有情侣了，无法发起新的邀请";
                            return null;
                        }
                    } else {
                        error = "用户不存在";
                        return null;
                    }

                    // 生成唯一邀请码
                    String code;
                    PreparedStatement checkCodeStmt = null;
                    do {
                        code = generateInviteCode();
                        String checkCodeSql = "SELECT id FROM users WHERE invite_code = ?";
                        checkCodeStmt = connection.prepareStatement(checkCodeSql);
                        checkCodeStmt.setString(1, code);
                        ResultSet codeRs = checkCodeStmt.executeQuery();
                        if (!codeRs.next()) {
                            break; // 邀请码唯一
                        }
                        codeRs.close();
                    } while (true);
                    if (checkCodeStmt != null) checkCodeStmt.close();

                    // 更新用户邀请码和状态
                    String updateSql = "UPDATE users SET invite_code = ?, couple_status = 'pending' WHERE id = ?";
                    updateStmt = connection.prepareStatement(updateSql);
                    updateStmt.setString(1, code);
                    updateStmt.setInt(2, userId);
                    
                    int rowsAffected = updateStmt.executeUpdate();
                    if (rowsAffected > 0) {
                        inviteCode = code;
                        return "邀请码生成成功";
                    } else {
                        error = "生成邀请码失败";
                        return null;
                    }

                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, checkStmt, updateStmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (error != null) {
                    callback.onError(error);
                } else if (inviteCode != null) {
                    callback.onInviteCodeGenerated(inviteCode);
                }
            }
        }.execute();
    }

    // 通过邀请码查找用户
    public void findUserByInviteCode(String inviteCode, UserSearchCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int foundUserId = -1;
            private String foundUsername = null;

            @Override
            protected Void doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();

                    String sql = "SELECT id, username FROM users WHERE invite_code = ? AND couple_status = 'pending'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setString(1, inviteCode);
                    rs = stmt.executeQuery();

                    if (rs.next()) {
                        foundUserId = rs.getInt("id");
                        foundUsername = rs.getString("username");
                    }

                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, rs, stmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (error != null) {
                    callback.onError(error);
                } else if (foundUserId != -1) {
                    callback.onUserFound(foundUserId, foundUsername);
                } else {
                    callback.onUserNotFound();
                }
            }
        }.execute();
    }

    // 创建情侣关系
    public void createCoupleRelationship(int inviterId, int inviteeId, CoupleCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String error = null;

            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement insertStmt = null;
                PreparedStatement updateStmt = null;
                PreparedStatement clearCodeStmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();
                    connection.setAutoCommit(false); // 开启事务

                    // 检查两个用户的状态
                    String checkSql = "SELECT id, couple_status FROM users WHERE id IN (?, ?) AND couple_status != 'coupled'";
                    PreparedStatement checkStmt = connection.prepareStatement(checkSql);
                    checkStmt.setInt(1, inviterId);
                    checkStmt.setInt(2, inviteeId);
                    rs = checkStmt.executeQuery();
                    
                    int validUsers = 0;
                    while (rs.next()) {
                        validUsers++;
                    }
                    checkStmt.close();
                    rs.close();
                    
                    if (validUsers != 2) {
                        error = "用户状态异常，无法建立情侣关系";
                        return null;
                    }

                    // 创建情侣关系记录
                    String insertSql = "INSERT INTO couple_relationships (user_id_1, user_id_2, status) VALUES (?, ?, 'active')";
                    insertStmt = connection.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS);
                    insertStmt.setInt(1, inviterId);
                    insertStmt.setInt(2, inviteeId);
                    
                    int rowsInserted = insertStmt.executeUpdate();
                    if (rowsInserted == 0) {
                        error = "创建情侣关系失败";
                        connection.rollback();
                        return null;
                    }

                    // 获取生成的relationship_id
                    rs = insertStmt.getGeneratedKeys();
                    int relationshipId = -1;
                    if (rs.next()) {
                        relationshipId = rs.getInt(1);
                    }
                    rs.close();

                    if (relationshipId == -1) {
                        error = "获取关系ID失败";
                        connection.rollback();
                        return null;
                    }

                    // 更新两个用户的状态和关系ID
                    String updateSql = "UPDATE users SET couple_status = 'coupled', relationship_id = ? WHERE id IN (?, ?)";
                    updateStmt = connection.prepareStatement(updateSql);
                    updateStmt.setInt(1, relationshipId);
                    updateStmt.setInt(2, inviterId);
                    updateStmt.setInt(3, inviteeId);
                    
                    int rowsUpdated = updateStmt.executeUpdate();
                    if (rowsUpdated != 2) {
                        error = "更新用户状态失败";
                        connection.rollback();
                        return null;
                    }

                    // 清空邀请码
                    String clearCodeSql = "UPDATE users SET invite_code = NULL WHERE id = ?";
                    clearCodeStmt = connection.prepareStatement(clearCodeSql);
                    clearCodeStmt.setInt(1, inviterId);
                    clearCodeStmt.executeUpdate();

                    connection.commit(); // 提交事务
                    return "情侣关系建立成功！";

                } catch (SQLException e) {
                    error = handleException(e);
                    try {
                        if (connection != null) connection.rollback();
                    } catch (SQLException rollbackEx) {
                        Log.e(TAG, "事务回滚失败", rollbackEx);
                    }
                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    try {
                        if (connection != null) connection.setAutoCommit(true);
                    } catch (SQLException e) {
                        Log.e(TAG, "重置自动提交失败", e);
                    }
                    closeResources(connection, rs, insertStmt, updateStmt, clearCodeStmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (error != null) {
                    callback.onError(error);
                } else if (result != null) {
                    callback.onSuccess(result);
                }
            }
        }.execute();
    }

    // 取消邀请（清空邀请码，状态改回single）
    public void cancelInvite(int userId, CoupleCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String error = null;

            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;

                try {
                    connection = getConnection();

                    String sql = "UPDATE users SET invite_code = NULL, couple_status = 'single' WHERE id = ? AND couple_status = 'pending'";
                    stmt = connection.prepareStatement(sql);
                    stmt.setInt(1, userId);
                    
                    int rowsAffected = stmt.executeUpdate();
                    if (rowsAffected > 0) {
                        return "邀请已取消";
                    } else {
                        error = "取消邀请失败";
                        return null;
                    }

                } catch (Exception e) {
                    error = handleException(e);
                } finally {
                    closeResources(connection, stmt);
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (error != null) {
                    callback.onError(error);
                } else if (result != null) {
                    callback.onSuccess(result);
                }
            }
        }.execute();
    }
}