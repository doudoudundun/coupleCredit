package com.example.couplecredit;

import android.os.AsyncTask;
import android.util.Log;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

public class CoupleRelationshipHelper {
    private static final String TAG = "CoupleRelationshipHelper";
    private static final String DB_USER = "demodb";
    private static final String DB_NAME = "demodb";
    private static final String DB_PASSWORD = "root";
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    // 获取数据库连接
    private Connection getConnection() throws ClassNotFoundException, SQLException {
        Class.forName("com.mysql.jdbc.Driver");
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // 关闭数据库资源
    private void closeResources(Connection connection, PreparedStatement... statements) {
        closeResources(connection, null, statements);
    }

    private void closeResources(Connection connection, ResultSet resultSet, PreparedStatement... statements) {
        try {
            if (resultSet != null) resultSet.close();
            for (PreparedStatement stmt : statements) {
                if (stmt != null) stmt.close();
            }
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
        void onCoupleFound(int coupleId, String coupleName);
        void onNoCoupleFound();
        void onError(String error);
    }

    public interface UnbindCallback {
        void onSuccess();
        void onError(String error);
    }

    // 获取情侣信息
    public void getCoupleInfo(int userId, CoupleInfoCallback callback) {
        new AsyncTask<Void, Void, Void>() {
            private String error = null;
            private int coupleId = -1;
            private String coupleName = null;

            @Override
            protected Void doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;

                try {
                    connection = getConnection();

                    // 查询当前用户的情侣关系
                    String sql = "SELECT cr.user_id_1, cr.user_id_2, u1.username as name1, u2.username as name2 " +
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
                        
                        // 确定情侣的ID和姓名（排除当前用户）
                        if (user1Id == userId) {
                            coupleId = user2Id;
                            coupleName = name2;
                        } else {
                            coupleId = user1Id;
                            coupleName = name1;
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
                    callback.onCoupleFound(coupleId, coupleName);
                } else {
                    callback.onNoCoupleFound();
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

                    // 更新情侣关系状态为inactive
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