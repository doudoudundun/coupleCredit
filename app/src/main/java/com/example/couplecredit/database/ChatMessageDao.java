package com.example.couplecredit.database;

import androidx.room.Dao;
import androidx.room.Delete;
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
     * 删除聊天消息
     * @param message 要删除的消息实体
     */
    @Delete
    void deleteMessage(ChatMessageEntity message);
    
    /**
     * 获取所有聊天消息，按创建时间升序排列
     * @return 所有聊天消息列表
     */
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    List<ChatMessageEntity> getAllMessages();
    
    /**
     * 根据关键词搜索聊天消息
     * @param keyword 搜索关键词
     * @return 包含关键词的消息列表
     */
    @Query("SELECT * FROM chat_messages WHERE content LIKE '%' || :keyword || '%' OR username LIKE '%' || :keyword || '%' ORDER BY createdAt ASC")
    List<ChatMessageEntity> searchMessages(String keyword);
    
    /**
     * 获取指定用户发送的所有消息
     * @param username 用户名
     * @return 该用户的所有消息
     */
    @Query("SELECT * FROM chat_messages WHERE username = :username ORDER BY createdAt ASC")
    List<ChatMessageEntity> getMessagesByUser(String username);
    
    /**
     * 删除所有聊天消息（清空聊天记录）
     */
    @Query("DELETE FROM chat_messages")
    void deleteAllMessages();
    
    /**
     * 获取消息总数
     * @return 消息总数
     */
    @Query("SELECT COUNT(*) FROM chat_messages")
    int getMessageCount();
}