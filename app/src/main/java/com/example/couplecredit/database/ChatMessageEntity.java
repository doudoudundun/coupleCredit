package com.example.couplecredit.database;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * 聊天消息实体类
 * 用于Room数据库存储聊天消息数据
 */
@Entity(tableName = "chat_messages")
public class ChatMessageEntity {
    
    /**
     * 消息唯一标识ID（主键，自动递增）
     */
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    /**
     * 发送者用户名
     */
    private String username;
    
    /**
     * 消息内容
     */
    private String content;
    
    /**
     * 消息发送时间戳
     */
    private String timestamp;
    
    /**
     * 用户头像资源ID
     */
    private int avatarResId;
    
    /**
     * 是否被点赞
     */
    private boolean isLiked;
    
    /**
     * 是否是本人发送的消息
     */
    private boolean isSentByMe;
    
    /**
     * 消息创建时间（用于排序）
     */
    private long createdAt;
    
    // 构造函数
    public ChatMessageEntity() {
        this.createdAt = System.currentTimeMillis();
    }
    
    @Ignore  // 添加此注解告诉Room忽略这个构造函数
    public ChatMessageEntity(String username, String content, String timestamp, 
                           int avatarResId, boolean isSentByMe) {
        this.username = username;
        this.content = content;
        this.timestamp = timestamp;
        this.avatarResId = avatarResId;
        this.isSentByMe = isSentByMe;
        this.isLiked = false;
        this.createdAt = System.currentTimeMillis();
    }
    
    // Getter和Setter方法
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public int getAvatarResId() { return avatarResId; }
    public void setAvatarResId(int avatarResId) { this.avatarResId = avatarResId; }
    
    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) { isLiked = liked; }
    
    public boolean isSentByMe() { return isSentByMe; }
    public void setSentByMe(boolean sentByMe) { isSentByMe = sentByMe; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}