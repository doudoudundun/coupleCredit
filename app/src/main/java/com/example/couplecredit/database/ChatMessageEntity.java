package com.example.couplecredit.database;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 聊天消息实体类
 * 用于Room数据库存储聊天消息数据
 * 同时支持与云端MySQL数据库的字段映射
 */
@Entity(
    tableName = "chat_messages",
    indices = {
        @Index("createdAt"),         // 排序用，几乎所有查询都 ORDER BY createdAt
        @Index("cloudMessageId"),    // 云端消息去重/查询
        @Index("username")           // 按用户筛选消息
    }
)
public class ChatMessageEntity {
    
    /**
     * 消息唯一标识ID（主键，自动递增）
     * 对应云端数据库的 id 字段
     */
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    /**
     * 关系ID（情侣关系标识）
     * 对应云端数据库的 relationship_id 字段
     */
    private long relationshipId;
    
    /**
     * 用户ID
     * 对应云端数据库的 user_id 字段
     */
    private int userId;
    
    /**
     * 发送者用户名
     * 对应云端数据库的 username 字段
     */
    private String username;
    
    /**
     * 消息内容
     * 对应云端数据库的 content 字段
     */
    private String content;
    
    /**
     * 消息类型（text, image, file等）
     * 对应云端数据库的 message_type 字段
     */
    private String messageType;
    
    /**
     * 消息发送时间戳
     * 对应云端数据库的 display_time 字段
     */
    private String timestamp;
    
    /**
     * 用户头像资源ID
     * 对应云端数据库的 avatar_res_id 字段
     */
    private int avatarResId;
    
    /**
     * 用户头像URL
     * 对应云端数据库的 avatar_url 字段
     */
    private String avatarUri;
    
    /**
     * 是否被点赞
     * 对应云端数据库的 is_liked 字段
     */
    private boolean isLiked;
    
    /**
     * 是否是本人发送的消息（本地字段，不同步到云端）
     */
    private boolean isSentByMe;
    
    /**
     * 是否已删除
     * 对应云端数据库的 is_deleted 字段
     */
    private boolean isDeleted;
    
    /**
     * 消息创建时间（用于排序）
     * 对应云端数据库的 created_at 字段
     */
    private long createdAt;
    
    /**
     * 消息更新时间
     * 对应云端数据库的 updated_at 字段
     */
    private long updatedAt;
    
    /**
     * 云端消息ID（用于本地和云端数据关联）
     */
    private Long cloudMessageId;
    
    /**
     * 关联的账单ID
     * 对应云端数据库的 bill_id 字段
     */
    private Long billId;
    
    /**
     * 是否为账单候选消息
     * 对应云端数据库的 is_bill_candidate 字段
     */
    private boolean isBillCandidate;
    
    /**
     * 同步状态（0-待同步，1-已同步，2-同步失败）
     */
    private int syncStatus;
    
    // 构造函数
    public ChatMessageEntity() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.messageType = "text";
        this.isDeleted = false;
        this.isBillCandidate = false;
        this.syncStatus = 0; // 默认未同步
    }
    
    @Ignore  // 添加此注解告诉Room忽略这个构造函数
    public ChatMessageEntity(String username, String content, String timestamp, 
                           int avatarResId, boolean isSentByMe) {
        this();
        this.username = username;
        this.content = content;
        this.timestamp = timestamp;
        this.avatarResId = avatarResId;
        this.isSentByMe = isSentByMe;
        this.isLiked = false;
    }
    
    @Ignore  // 云端数据构造函数
    public ChatMessageEntity(long relationshipId, int userId, String content, 
                           String messageType, String timestamp, String avatarUrl, 
                           boolean isLiked, Long billId, boolean isBillCandidate) {
        this();
        this.relationshipId = relationshipId;
        this.userId = userId;
        this.content = content;
        this.messageType = messageType;
        this.timestamp = timestamp;
        this.avatarUri = avatarUrl;
        this.isLiked = isLiked;
        this.billId = billId;
        this.isBillCandidate = isBillCandidate;
        this.syncStatus = 1; // 来自云端，标记为已同步
    }
    
    // Getter和Setter方法
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    
    public long getRelationshipId() { return relationshipId; }
    public void setRelationshipId(long relationshipId) { this.relationshipId = relationshipId; }
    
    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getContent() { return content; }
    public void setContent(String content) { 
        this.content = content;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public int getAvatarResId() { return avatarResId; }
    public void setAvatarResId(int avatarResId) { this.avatarResId = avatarResId; }
    
    public String getAvatarUri() { return avatarUri; }
    public void setAvatarUri(String avatarUri) { this.avatarUri = avatarUri; }
    
    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) { 
        isLiked = liked;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public boolean isSentByMe() { return isSentByMe; }
    public void setSentByMe(boolean sentByMe) { isSentByMe = sentByMe; }
    
    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean deleted) { 
        isDeleted = deleted;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    
    public Long getCloudMessageId() { return cloudMessageId; }
    public void setCloudMessageId(Long cloudMessageId) { this.cloudMessageId = cloudMessageId; }
    
    public int getSyncStatus() { return syncStatus; }
    public void setSyncStatus(int syncStatus) { this.syncStatus = syncStatus; }
    
    public Long getBillId() { return billId; }
    public void setBillId(Long billId) { 
        this.billId = billId;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public boolean isBillCandidate() { return isBillCandidate; }
    public void setBillCandidate(boolean billCandidate) { 
        this.isBillCandidate = billCandidate;
        this.updatedAt = System.currentTimeMillis();
    }
    
    // 辅助方法
    
    /**
     * 检查消息是否需要同步到云端
     */
    public boolean needsSync() {
        return syncStatus == 0 || syncStatus == 2; // 未同步或同步失败
    }
    
    /**
     * 标记为已同步
     */
    public void markAsSynced(Long cloudId) {
        this.cloudMessageId = cloudId;
        this.syncStatus = 1;
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 标记为同步失败
     */
    public void markSyncFailed() {
        this.syncStatus = 2;
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 获取显示用的头像（优先使用URL，其次使用资源ID）
     */
    public String getDisplayAvatar() {
        return avatarUri != null && !avatarUri.isEmpty() ? avatarUri : String.valueOf(avatarResId);
    }
    
    /**
     * 创建云端数据的副本（用于上传）
     */
    public ChatMessageEntity createCloudCopy() {
        ChatMessageEntity copy = new ChatMessageEntity();
        copy.relationshipId = this.relationshipId;
        copy.userId = this.userId;
        copy.username = this.username;
        copy.content = this.content;
        copy.messageType = this.messageType;
        copy.timestamp = this.timestamp;
        copy.avatarResId = this.avatarResId;
        copy.avatarUri = this.avatarUri;
        copy.isLiked = this.isLiked;
        copy.isDeleted = this.isDeleted;
        copy.billId = this.billId;
        copy.isBillCandidate = this.isBillCandidate;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;
        return copy;
    }
    
    // 同步状态常量
    public static final int SYNC_STATUS_PENDING = 0;   // 待同步
    public static final int SYNC_STATUS_SYNCED = 1;    // 已同步
    public static final int SYNC_STATUS_FAILED = 2;    // 同步失败
    
    // 消息类型常量
    public static final String MESSAGE_TYPE_TEXT = "text";
    public static final String MESSAGE_TYPE_IMAGE = "image";
    public static final String MESSAGE_TYPE_FILE = "file";
    public static final String MESSAGE_TYPE_EMOJI = "emoji";
}