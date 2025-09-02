package com.example.couplecredit.repository;

import android.content.Context;
import android.util.Log;

import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import com.example.couplecredit.function.NicknameCache;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云端聊天数据仓库类
 * 负责与阿里云MySQL数据库进行交互，实现聊天消息的云端存储
 * 提供完整的CRUD操作和数据同步功能
 */
public class CloudChatRepository {
    
    private static final String TAG = "CloudChatRepository";
    
    // 数据库连接配置（从ChatMessageHelper获取）
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "demodb";
    private static final String DB_USER = "demodb";
    private static final String DB_PASSWORD = "root";
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    // 线程池用于异步数据库操作
    private final ExecutorService databaseExecutor;
    
    // 当前用户信息
    private int currentRelationshipId = -1; // 从UserInfoManager获取
    private int currentUserId = -1; // 从UserInfoManager获取
    private String currentUsername = null; // 从UserInfoManager获取
    private Context context; // 用于获取用户信息
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public CloudChatRepository(Context context) {
        this.context = context;
        this.databaseExecutor = Executors.newFixedThreadPool(4);
        
        // 加载MySQL驱动
        try {
            Class.forName("com.mysql.jdbc.Driver");
            Log.d(TAG, "MySQL驱动加载成功");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "MySQL驱动加载失败", e);
        }
        
        // 初始化用户信息
        initializeUserInfo();
    }
    
    /**
     * 获取数据库连接
     * @return 数据库连接对象
     * @throws SQLException 连接异常
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }
    
    /**
     * 初始化用户信息
     */
    public void initializeUserInfo() {
        if (UserInfoManager.isUserLoggedIn(context)) {
            UserInfoManager.getCurrentUserInfo(context, new UserInfoManager.UserInfoCallback() {
                @Override
                public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                    currentUserId = userId;
                    currentUsername = username;
                    currentRelationshipId = relationshipId != null ? relationshipId : -1;
                    Log.d(TAG, "用户信息初始化成功: userId=" + userId + ", username=" + username + ", relationshipId=" + relationshipId);
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "获取用户信息失败: " + error);
                }
            });
        } else {
            Log.w(TAG, "用户未登录，无法初始化用户信息");
        }
    }
    
    /**
     * 插入聊天消息到云端数据库
     * @param message 聊天消息对象
     * @param userId 用户ID
     * @param callback 插入完成后的回调
     */
    public void insertMessage(ChatMessage message, int userId, InsertCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            
            try {
                conn = getConnection();
                
                // SQL插入语句，对应新的云端chat_messages表结构
                String sql = "INSERT INTO chat_messages (relationship_id, user_id, content, " +
                           "message_type, display_time, created_at, avatar_url, is_liked, is_deleted, " +
                           "bill_id, is_bill_candidate) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                
                stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                // 确保有有效的关系ID
                if (currentRelationshipId <= 0) {
                    Log.w(TAG, "关系ID无效，尝试重新获取用户信息");
                    initializeUserInfo();
                    if (currentRelationshipId <= 0) {
                        if (callback != null) {
                            callback.onError(new SQLException("无效的关系ID，用户可能未绑定情侣关系"));
                        }
                        return;
                    }
                }
                
                stmt.setInt(1, currentRelationshipId);  // 关系ID
                stmt.setInt(2, userId);                 // 用户ID
                stmt.setString(3, message.getContent()); // 消息内容
                stmt.setString(4, "text");              // 消息类型，默认为文本
                stmt.setString(5, message.getTimestamp()); // 显示时间
                stmt.setLong(6, System.currentTimeMillis()); // 创建时间戳
                stmt.setString(7, null);                // 头像URL，暂时为null
                stmt.setBoolean(8, message.isLiked());   // 是否点赞
                stmt.setBoolean(9, false);               // 是否删除，默认false
                stmt.setObject(10, null);               // bill_id，默认null
                stmt.setBoolean(11, false);             // is_bill_candidate，默认false
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    // 获取生成的主键ID
                    ResultSet generatedKeys = stmt.getGeneratedKeys();
                    if (generatedKeys.next()) {
                        long messageId = generatedKeys.getLong(1);
                        Log.d(TAG, "消息插入成功，ID: " + messageId);
                        
                        if (callback != null) {
                            callback.onSuccess(messageId);
                        }
                    }
                } else {
                    Log.e(TAG, "消息插入失败，没有受影响的行");
                    if (callback != null) {
                        callback.onError(new SQLException("插入失败，没有受影响的行"));
                    }
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "插入消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                // 关闭资源
                closeResources(conn, stmt, null);
            }
        });
    }
    
    // 添加新的insertMessage方法重载，匹配ChatSyncService的调用
    public void insertMessage(long relationshipId, int userId, String content, 
                             String messageType, String timestamp, String avatarUrl, 
                             boolean isLiked, InsertCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            
            try {
                conn = getConnection();
                
                // SQL插入语句，对应新的云端chat_messages表结构
                String sql = "INSERT INTO chat_messages (relationship_id, user_id, content, " +
                           "message_type, display_time, created_at, avatar_url, is_liked, is_deleted, " +
                           "bill_id, is_bill_candidate) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                
                stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                stmt.setLong(1, relationshipId);        // 关系ID
                stmt.setInt(2, userId);                // 用户ID
                stmt.setString(3, content);             // 消息内容
                stmt.setString(4, messageType);         // 消息类型
                stmt.setString(5, timestamp);           // 显示时间
                stmt.setLong(6, System.currentTimeMillis()); // 创建时间戳
                stmt.setString(7, avatarUrl);           // 头像URL
                stmt.setBoolean(8, isLiked);            // 是否点赞
                stmt.setBoolean(9, false);              // 是否删除，默认false
                stmt.setObject(10, null);               // bill_id，默认null
                stmt.setBoolean(11, false);             // is_bill_candidate，默认false
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    // 获取生成的主键ID
                    ResultSet generatedKeys = stmt.getGeneratedKeys();
                    if (generatedKeys.next()) {
                        long messageId = generatedKeys.getLong(1);
                        Log.d(TAG, "消息插入成功，ID: " + messageId);
                        
                        if (callback != null) {
                            callback.onSuccess(messageId);
                        }
                    }
                } else {
                    Log.e(TAG, "消息插入失败，没有受影响的行");
                    if (callback != null) {
                        callback.onError(new SQLException("插入失败，没有受影响的行"));
                    }
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "插入消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                // 关闭资源
                closeResources(conn, stmt, null);
            }
        });
    }
    
    /**
     * 从云端数据库获取所有聊天消息
     * @param callback 查询完成后的回调
     */
    public void getAllMessages(QueryCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            
            try {
                // 确保有有效的关系ID
                if (currentRelationshipId <= 0) {
                    Log.w(TAG, "关系ID无效，尝试重新获取用户信息");
                    initializeUserInfo();
                    if (currentRelationshipId <= 0) {
                        if (callback != null) {
                            callback.onError(new SQLException("无效的关系ID，用户可能未绑定情侣关系"));
                        }
                        return;
                    }
                }
                
                conn = getConnection();
                
                // 查询当前关系的所有未删除消息，按创建时间升序排列
                String sql = "SELECT id, user_id, content, message_type, display_time, " +
                           "avatar_url, is_liked, created_at, bill_id, is_bill_candidate " +
                           "FROM chat_messages " +
                           "WHERE relationship_id = ? AND is_deleted = 0 " +
                           "ORDER BY created_at ASC";
                
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, currentRelationshipId);
                
                rs = stmt.executeQuery();
                
                List<ChatMessage> messages = new ArrayList<>();
                
                while (rs.next()) {
                    // 将数据库记录转换为ChatMessage对象
                    // 需要根据user_id判断是否为当前用户发送的消息
                    int messageUserId = rs.getInt("user_id");
                    boolean isSentByMe = (messageUserId == currentUserId);
                    
                    ChatMessage message = new ChatMessage(
                        getUsernameById(messageUserId), // 根据user_id获取用户名
                        messageUserId, // 设置用户ID
                        rs.getString("content"),
                        rs.getString("display_time"),
                        getAvatarResourceId(messageUserId), // 根据user_id获取头像资源ID
                        null, // avatarUri
                        isSentByMe
                    );
                    
                    message.setLiked(rs.getBoolean("is_liked"));
                    
                    // 设置消息ID（如果ChatMessage类支持）
                    // message.setId(rs.getLong("id"));
                    
                    messages.add(message);
                }
                
                Log.d(TAG, "成功获取 " + messages.size() + " 条消息");
                
                if (callback != null) {
                    callback.onSuccess(messages);
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "查询消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                closeResources(conn, stmt, rs);
            }
        });
    }
    
    /**
     * 更新消息（主要用于点赞状态）
     * @param messageContent 消息内容（用于定位消息）
     * @param timestamp 消息时间戳（用于定位消息）
     * @param isLiked 新的点赞状态
     * @param callback 更新完成后的回调
     */
    public void updateMessageLikeStatus(String messageContent, String timestamp, boolean isLiked, UpdateCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            
            try {
                conn = getConnection();
                
                // 根据内容和时间戳更新点赞状态
                String sql = "UPDATE chat_messages SET is_liked = ? " +
                           "WHERE relationship_id = ? AND content = ? AND display_time = ? AND is_deleted = 0";
                
                stmt = conn.prepareStatement(sql);
                stmt.setBoolean(1, isLiked);
                stmt.setInt(2, currentRelationshipId);
                stmt.setString(3, messageContent);
                stmt.setString(4, timestamp);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    Log.d(TAG, "消息点赞状态更新成功");
                    if (callback != null) {
                        callback.onSuccess();
                    }
                } else {
                    Log.w(TAG, "没有找到匹配的消息进行更新");
                    if (callback != null) {
                        callback.onError(new SQLException("没有找到匹配的消息"));
                    }
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "更新消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                closeResources(conn, stmt, null);
            }
        });
    }
    
    /**
     * 删除消息（软删除，设置is_deleted标志）
     * @param messageContent 消息内容
     * @param timestamp 消息时间戳
     * @param callback 删除完成后的回调
     */
    public void deleteMessage(String messageContent, String timestamp, DeleteCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            
            try {
                conn = getConnection();
                
                // 软删除：设置is_deleted标志为true
                String sql = "UPDATE chat_messages SET is_deleted = 1 " +
                           "WHERE relationship_id = ? AND content = ? AND display_time = ? AND is_deleted = 0";
                
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, currentRelationshipId);
                stmt.setString(2, messageContent);
                stmt.setString(3, timestamp);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    Log.d(TAG, "消息删除成功");
                    if (callback != null) {
                        callback.onSuccess();
                    }
                } else {
                    Log.w(TAG, "没有找到匹配的消息进行删除");
                    if (callback != null) {
                        callback.onError(new SQLException("没有找到匹配的消息"));
                    }
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "删除消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                closeResources(conn, stmt, null);
            }
        });
    }
    
    /**
     * 搜索消息
     * @param keyword 搜索关键词
     * @param callback 搜索完成后的回调
     */
    public void searchMessages(String keyword, QueryCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            
            try {
                conn = getConnection();
                
                // 在内容和用户名中搜索关键词
                String sql = "SELECT id, user_id, username, content, message_type, display_time, " +
                           "avatar_res_id, avatar_url, is_liked, created_at " +
                           "FROM chat_messages " +
                           "WHERE relationship_id = ? AND is_deleted = 0 " +
                           "AND (content LIKE ? OR username LIKE ?) " +
                           "ORDER BY created_at ASC";
                
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, currentRelationshipId);
                String searchPattern = "%" + keyword + "%";
                stmt.setString(2, searchPattern);
                stmt.setString(3, searchPattern);
                
                rs = stmt.executeQuery();
                
                List<ChatMessage> messages = new ArrayList<>();
                
                while (rs.next()) {
                    ChatMessage message = new ChatMessage(
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getString("display_time"),
                        rs.getInt("avatar_res_id"),
                        false // isSentByMe需要根据当前用户判断
                    );
                    
                    message.setLiked(rs.getBoolean("is_liked"));
                    messages.add(message);
                }
                
                Log.d(TAG, "搜索到 " + messages.size() + " 条匹配消息");
                
                if (callback != null) {
                    callback.onSuccess(messages);
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "搜索消息时发生数据库错误", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                closeResources(conn, stmt, rs);
            }
        });
    }
    
    /**
     * 测试数据库连接
     * @param callback 测试完成后的回调
     */
    public void testConnection(ConnectionTestCallback callback) {
        databaseExecutor.execute(() -> {
            Connection conn = null;
            
            try {
                conn = getConnection();
                
                if (conn != null && !conn.isClosed()) {
                    Log.d(TAG, "数据库连接测试成功");
                    if (callback != null) {
                        callback.onSuccess("连接成功");
                    }
                } else {
                    Log.e(TAG, "数据库连接测试失败");
                    if (callback != null) {
                        callback.onError(new SQLException("连接失败"));
                    }
                }
                
            } catch (SQLException e) {
                Log.e(TAG, "数据库连接测试异常", e);
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        Log.e(TAG, "关闭连接时发生错误", e);
                    }
                }
            }
        });
    }
    
    /**
     * 关闭数据库资源
     * @param conn 数据库连接
     * @param stmt 预处理语句
     * @param rs 结果集
     */
    private void closeResources(Connection conn, PreparedStatement stmt, ResultSet rs) {
        try {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        } catch (SQLException e) {
            Log.e(TAG, "关闭数据库资源时发生错误", e);
        }
    }
    
    /**
     * 设置当前关系ID
     * @param relationshipId 关系ID
     */
    public void setCurrentRelationshipId(int relationshipId) {
        this.currentRelationshipId = relationshipId;
    }
    
    /**
     * 设置当前用户信息
     * @param userId 用户ID
     * @param username 用户名
     */
    public void setCurrentUserInfo(int userId, String username) {
        this.currentUserId = userId;
        this.currentUsername = username;
        Log.d(TAG, "设置当前用户信息: ID=" + userId + ", 用户名=" + username);
    }
    
    /**
     * 设置当前用户信息（包含关系ID）
     * @param userId 用户ID
     * @param username 用户名
     * @param relationshipId 关系ID
     */
    public void setCurrentUserInfo(int userId, String username, Integer relationshipId) {
        this.currentUserId = userId;
        this.currentUsername = username;
        this.currentRelationshipId = relationshipId != null ? relationshipId : -1;
        Log.d(TAG, "设置当前用户信息: ID=" + userId + ", 用户名=" + username + ", 关系ID=" + relationshipId);
    }
    
    /**
     * 获取当前用户ID
     * @return 当前用户ID
     */
    private int getCurrentUserId() {
        return currentUserId;
    }
    
    /**
     * 根据用户ID获取用户名
     * @param userId 用户ID
     * @return 用户名
     */
    private String getUsernameById(int userId) {
        // 优先从缓存获取昵称
        String cachedNickname = null;
        if (userId == currentUserId && currentUsername != null) {
            cachedNickname = NicknameCache.getCachedNickname(context, currentUsername);
            if (cachedNickname != null) {
                return cachedNickname;
            }
        }
        
        // 缓存中没有，尝试从数据库获取昵称（同步方式）
        try {
            String nickname = getUserNicknameByIdSync(userId);
            if (nickname != null && !nickname.isEmpty()) {
                // 缓存昵称
                if (userId == currentUserId && currentUsername != null) {
                    NicknameCache.cacheNickname(context, currentUsername, nickname);
                }
                return nickname;
            }
        } catch (Exception e) {
            Log.w(TAG, "获取用户昵称失败: " + e.getMessage());
        }
        
        // 如果获取昵称失败，返回默认值
        if (userId == currentUserId) {
            return currentUsername != null ? currentUsername : "我";
        } else {
            return "伴侣";
        }
    }
    
    /**
     * 同步获取用户昵称（仅在数据库线程中调用）
     * @param userId 用户ID
     * @return 用户昵称，如果没有昵称则返回用户名
     */
    private String getUserNicknameByIdSync(int userId) throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = getConnection();
            String sql = "SELECT nickname, username FROM users WHERE id = ? AND status = 'active'";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, userId);
            
            rs = stmt.executeQuery();
            
            if (rs.next()) {
                String nickname = rs.getString("nickname");
                String username = rs.getString("username");
                // 如果昵称为空或null，返回用户名
                return (nickname != null && !nickname.trim().isEmpty()) ? nickname : username;
            } else {
                return null;
            }
        } finally {
            closeResources(conn, stmt, rs);
        }
    }
    
    /**
     * 根据用户ID获取头像资源ID
     * @param userId 用户ID
     * @return 头像资源ID
     */
    private int getAvatarResourceId(int userId) {
        // 简化实现：根据用户ID返回不同的头像资源
        if (userId == currentUserId) {
            return android.R.drawable.ic_menu_myplaces; // 当前用户头像
        } else {
            return android.R.drawable.ic_menu_gallery; // 伴侣头像
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
    }
    
    // ==================== 回调接口定义 ====================
    
    /**
     * 插入操作回调接口
     */
    public interface InsertCallback {
        void onSuccess(long messageId);
        void onError(Exception e);
    }
    
    /**
     * 查询操作回调接口
     */
    public interface QueryCallback {
        void onSuccess(List<ChatMessage> messages);
        void onError(Exception e);
    }
    
    /**
     * 更新操作回调接口
     */
    public interface UpdateCallback {
        void onSuccess();
        void onError(Exception e);
    }
    
    /**
     * 删除操作回调接口
     */
    public interface DeleteCallback {
        void onSuccess();
        void onError(Exception e);
    }
    
    /**
     * 连接测试回调接口
     */
    public interface ConnectionTestCallback {
        void onSuccess(String message);
        void onError(Exception e);
    }
}