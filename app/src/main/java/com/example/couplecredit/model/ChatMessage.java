package com.example.couplecredit.model;

/**
 * 聊天消息数据模型类
 * 用于表示聊天界面中的单条消息信息
 * 
 * 该类包含了消息的所有基本属性：
 * - 用户信息（用户名、头像）
 * - 消息内容和时间戳
 * - 交互状态（点赞状态、发送者标识）
 */
public class ChatMessage {
    
    // ==================== 属性字段 ====================
    
    private String username;    // 用户名
    private int userId;          // 用户ID（新增）
    private String content;     // 消息内容
    private String timestamp;   // 时间戳
    private int avatarResId;    // 头像资源ID
    private String avatarUri;   // 头像URI（新增）
    private boolean isLiked;    // 是否被点赞
    private boolean isSentByMe; // 是否是本人发送的消息
    
    // ==================== 新增字段 ====================
    private long id;            // 消息ID - 本地数据库主键
    private Long cloudMessageId; // 云端消息ID - 用于云端数据同步
    private long relationshipId; // 关系ID
    private String messageType; // 消息类型
    
    // ==================== 构造函数 ====================
    
    /**
     * 完整构造函数（支持头像URI和用户ID）
     */
    public ChatMessage(String username, int userId, String content, String timestamp, int avatarResId, String avatarUri, boolean isSentByMe) {
        this.username = username;
        this.userId = userId;
        this.content = content;
        this.timestamp = timestamp;
        this.avatarResId = avatarResId;
        this.avatarUri = avatarUri;
        this.isLiked = false;
        this.isSentByMe = isSentByMe;
    }
    
    /**
     * 完整构造函数
     */
    public ChatMessage(String username, String content, String timestamp, int avatarResId, boolean isSentByMe) {
        this(username, 0, content, timestamp, avatarResId, null, isSentByMe);
    }
    
    /**
     * 简化构造函数（保持向后兼容）
     * 默认消息不是本人发送
     *
     * @param username    用户名
     * @param content     消息内容
     * @param timestamp   时间戳
     * @param avatarResId 头像资源ID
     */
    public ChatMessage(String username, String content, String timestamp, int avatarResId) {
        this(username, content, timestamp, avatarResId, false);
    }
    
    // ==================== Getter 方法 ====================
    
    /**
     * 获取用户名
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * 获取消息内容
     * @return 消息内容
     */
    public String getContent() {
        return content;
    }
    
    /**
     * 获取时间戳
     * @return 时间戳字符串
     */
    public String getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取头像资源ID
     * @return 头像资源ID
     */
    public int getAvatarResId() {
        return avatarResId;
    }
    
    /**
     * 获取头像URI
     * @return 头像URI字符串
     */
    public String getAvatarUri() {
        return avatarUri;
    }
    
    public void setAvatarUri(String avatarUri) {
        this.avatarUri = avatarUri;
    }
    
    // 添加getAvatarUrl()方法作为getAvatarUri()的别名，解决编译错误
    public String getAvatarUrl() {
        return getAvatarUri();
    }
    
    public boolean hasCustomAvatar() {
        return avatarUri != null && !avatarUri.isEmpty();
    }
    
    /**
     * 获取点赞状态
     * @return true表示已点赞，false表示未点赞
     */
    public boolean isLiked() {
        return isLiked;
    }
    
    /**
     * 判断是否是本人发送的消息
     * @return true表示是本人发送，false表示是对方发送
     */
    public boolean isSentByMe() {
        return isSentByMe;
    }
    
    // ==================== Setter 方法 ====================
    
    /**
     * 设置点赞状态
     * @param liked 点赞状态
     */
    public void setLiked(boolean liked) {
        isLiked = liked;
    }
    
    /**
     * 设置消息发送者标识
     * @param sentByMe 是否是本人发送
     */
    public void setSentByMe(boolean sentByMe) {
        isSentByMe = sentByMe;
    }
    
    /**
     * 获取用户ID
     * @return 用户ID
     */
    public int getUserId() {
        return userId;
    }

    /**
     * 设置用户ID
     * @param userId 用户ID
     */
    public void setUserId(int userId) {
        this.userId = userId;
    }
    
    // ==================== 新增：缺失的setter方法 ====================
    
    /**
     * 获取消息ID
     * @return 消息ID
     */
    public long getId() {
        return id;
    }

    /**
     * 设置消息ID
     * @param id 消息ID
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * 获取云端消息ID
     * @return 云端消息ID
     */
    public Long getCloudMessageId() {
        return cloudMessageId;
    }

    /**
     * 设置云端消息ID
     * @param cloudMessageId 云端消息ID
     */
    public void setCloudMessageId(Long cloudMessageId) {
        this.cloudMessageId = cloudMessageId;
    }
    
    /**
     * 获取关系ID
     * @return 关系ID
     */
    public long getRelationshipId() {
        return relationshipId;
    }
    
    /**
     * 设置关系ID
     * @param relationshipId 关系ID
     */
    public void setRelationshipId(long relationshipId) {
        this.relationshipId = relationshipId;
    }
    
    /**
     * 获取消息类型
     * @return 消息类型
     */
    public String getMessageType() {
        return messageType;
    }
    
    /**
     * 设置消息类型
     * @param messageType 消息类型
     */
    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }
    
    /**
     * 设置用户名
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }
    
    /**
     * 设置消息内容
     * @param content 消息内容
     */
    public void setContent(String content) {
        this.content = content;
    }
    
    /**
     * 设置时间戳
     * @param timestamp 时间戳
     */
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
    /**
     * 设置头像资源ID
     * @param avatarResId 头像资源ID
     */
    public void setAvatarResId(int avatarResId) {
        this.avatarResId = avatarResId;
    }
    
    
    /**
     * 工具方法 ====================
     */
    
    /**
     * 重写toString方法，便于调试
     * @return 消息的字符串表示
     */
    @Override
    public String toString() {
        return "ChatMessage{" +
                "username='" + username + '\'' +
                ", content='" + content + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", avatarResId=" + avatarResId +
                ", isLiked=" + isLiked +
                ", isSentByMe=" + isSentByMe +
                '}';
    }
    
    /**
     * 重写equals方法，用于消息比较
     * @param obj 比较对象
     * @return 是否相等
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ChatMessage that = (ChatMessage) obj;
        
        return avatarResId == that.avatarResId &&
                isLiked == that.isLiked &&
                isSentByMe == that.isSentByMe &&
                username.equals(that.username) &&
                content.equals(that.content) &&
                timestamp.equals(that.timestamp);
    }
    
    /**
     * 重写hashCode方法
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        int result = username.hashCode();
        result = 31 * result + content.hashCode();
        result = 31 * result + timestamp.hashCode();
        result = 31 * result + avatarResId;
        result = 31 * result + (isLiked ? 1 : 0);
        result = 31 * result + (isSentByMe ? 1 : 0);
        return result;
    }
}