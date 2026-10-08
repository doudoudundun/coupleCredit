package com.example.couplecredit.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 聊天消息数据访问对象
 * 定义对chat_messages表的所有数据库操作
 */
@Dao
public interface ChatMessageDao {
    
    /**
     * 插入新的聊天消息
     * @param message 要插入的消息实体
     * @return 插入消息的ID
     */
    @Insert
    long insertMessage(ChatMessageEntity message);
    
    /**
     * 批量插入聊天消息
     * @param messages 要插入的消息列表
     */
    @Insert
    void insertMessages(List<ChatMessageEntity> messages);
    
    /**
     * 更新聊天消息（主要用于更新点赞状态）
     * @param message 要更新的消息实体
     */
    @Update
    void updateMessage(ChatMessageEntity message);
    
    /**
     * 获取当前情侣关系的聊天消息，按创建时间升序排列。
     */
    @Query("SELECT * FROM chat_messages WHERE relationshipId = :relationshipId ORDER BY createdAt ASC")
    List<ChatMessageEntity> getMessagesForRelationship(long relationshipId);

    /**
     * 获取尚未上传到云端的消息。
     */
    @Query("SELECT * FROM chat_messages WHERE relationshipId = :relationshipId AND syncStatus != 1 AND cloudMessageId IS NULL ORDER BY createdAt ASC")
    List<ChatMessageEntity> getPendingMessages(long relationshipId);

    /**
     * 只删除已同步的本地缓存，保留离线期间产生的待同步消息。
     */
    @Query("DELETE FROM chat_messages WHERE relationshipId = :relationshipId AND (syncStatus = 1 OR cloudMessageId IS NOT NULL)")
    void deleteSyncedMessages(long relationshipId);
    
    /**
     * 根据关键词搜索聊天消息
     * @param keyword 搜索关键词
     * @return 包含关键词的消息列表
     */
    @Query("SELECT * FROM chat_messages WHERE relationshipId = :relationshipId AND (content LIKE '%' || :keyword || '%' OR username LIKE '%' || :keyword || '%') ORDER BY createdAt ASC")
    List<ChatMessageEntity> searchMessages(long relationshipId, String keyword);
    
    /**
     * 退出登录时擦除设备上的全部聊天缓存，避免下一位登录用户读取残留数据。
     * 普通关系切换不得调用此方法。
     */
    @Query("DELETE FROM chat_messages")
    void deleteAllMessagesForLogout();
    
    /**
     * 根据消息ID获取消息
     * @param id 消息ID
     * @return 对应的消息实体，如果不存在则返回null
     */
    @Query("SELECT * FROM chat_messages WHERE id = :id AND relationshipId = :relationshipId")
    ChatMessageEntity getMessageById(long id, long relationshipId);
    
    /**
     * 根据本地消息ID删除消息
     * @param id 本地消息ID
     */
    @Query("DELETE FROM chat_messages WHERE id = :id AND relationshipId = :relationshipId")
    void deleteById(long id, long relationshipId);

    /**
     * 根据云端消息ID删除消息
     * @param cloudMessageId 云端消息ID
     */
    @Query("DELETE FROM chat_messages WHERE cloudMessageId = :cloudMessageId AND relationshipId = :relationshipId")
    void deleteByCloudId(long cloudMessageId, long relationshipId);
}
