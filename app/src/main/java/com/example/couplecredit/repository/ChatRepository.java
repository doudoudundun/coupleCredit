package com.example.couplecredit.repository;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.database.ChatDatabase;
import com.example.couplecredit.database.ChatMessageDao;
import com.example.couplecredit.database.ChatMessageEntity;
import com.example.couplecredit.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天数据仓库类
 * 负责管理聊天消息的数据访问，包括数据库操作和数据转换
 * 实现MVVM架构中的Repository层，为ViewModel提供统一的数据接口
 */
public class ChatRepository {
    
    // ==================== 成员变量 ====================
    
    private final ChatMessageDao chatMessageDao;
    private final ExecutorService databaseExecutor;
    private final MutableLiveData<List<ChatMessage>> allMessagesLiveData;
    private final MutableLiveData<List<ChatMessage>> searchResultsLiveData;
    
    // ==================== 构造函数 ====================
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public ChatRepository(Context context) {
        ChatDatabase database = ChatDatabase.getInstance(context);
        chatMessageDao = database.chatMessageDao();
        databaseExecutor = Executors.newFixedThreadPool(4);
        allMessagesLiveData = new MutableLiveData<>();
        searchResultsLiveData = new MutableLiveData<>();
    }
    
    // ==================== 公共接口方法 ====================
    
    /**
     * 获取所有消息的LiveData
     * @return 包含所有消息的LiveData
     */
    public LiveData<List<ChatMessage>> getAllMessages() {
        return allMessagesLiveData;
    }
    
    /**
     * 获取搜索结果的LiveData
     * @return 包含搜索结果的LiveData
     */
    public LiveData<List<ChatMessage>> getSearchResults() {
        return searchResultsLiveData;
    }
    
    /**
     * 从数据库加载所有消息
     */
    public void loadAllMessages() {
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                allMessagesLiveData.postValue(messages);
            } catch (Exception e) {
                e.printStackTrace();
                // 如果数据库为空或出错，加载默认消息
                loadDefaultMessages();
            }
        });
    }
    
    /**
     * 插入新消息
     * @param message 要插入的消息
     * @param callback 插入完成后的回调
     */
    public void insertMessage(ChatMessage message, InsertCallback callback) {
        databaseExecutor.execute(() -> {
            try {
                ChatMessageEntity entity = convertMessageToEntity(message);
                long id = chatMessageDao.insertMessage(entity);
                
                // 重新加载所有消息
                loadAllMessages();
                
                if (callback != null) {
                    callback.onSuccess(id);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 更新消息（主要用于点赞状态）
     * @param message 要更新的消息
     * @param callback 更新完成后的回调
     */
    public void updateMessage(ChatMessage message, UpdateCallback callback) {
        databaseExecutor.execute(() -> {
            try {
                // 根据内容和时间戳查找并更新消息
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                for (ChatMessageEntity entity : entities) {
                    if (entity.getContent().equals(message.getContent()) && 
                        entity.getTimestamp().equals(message.getTimestamp())) {
                        entity.setLiked(message.isLiked());
                        chatMessageDao.updateMessage(entity);
                        break;
                    }
                }
                
                // 重新加载所有消息
                loadAllMessages();
                
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 删除消息
     * @param message 要删除的消息
     * @param callback 删除完成后的回调
     */
    public void deleteMessage(ChatMessage message, DeleteCallback callback) {
        databaseExecutor.execute(() -> {
            try {
                chatMessageDao.deleteByContentAndTimestamp(message.getContent(), message.getTimestamp());
                
                // 重新加载所有消息
                loadAllMessages();
                
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 搜索消息
     * @param keyword 搜索关键词
     */
    public void searchMessages(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // 如果搜索关键词为空，返回所有消息
            searchResultsLiveData.postValue(allMessagesLiveData.getValue());
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.searchMessages(keyword.trim());
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                searchResultsLiveData.postValue(messages);
            } catch (Exception e) {
                e.printStackTrace();
                searchResultsLiveData.postValue(new ArrayList<>());
            }
        });
    }
    
    /**
     * 保存默认消息到数据库
     */
    public void saveDefaultMessages() {
        databaseExecutor.execute(() -> {
            try {
                // 检查数据库是否为空
                int count = chatMessageDao.getMessageCount();
                if (count == 0) {
                    List<ChatMessageEntity> defaultMessages = createDefaultMessages();
                    chatMessageDao.insertMessages(defaultMessages);
                    loadAllMessages();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 将数据库实体转换为业务模型
     * @param entities 数据库实体列表
     * @return 业务模型列表
     */
    private List<ChatMessage> convertEntitiesToMessages(List<ChatMessageEntity> entities) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageEntity entity : entities) {
            ChatMessage message = new ChatMessage(
                entity.getUsername(),
                entity.getContent(),
                entity.getTimestamp(),
                entity.getAvatarResId(),
                entity.isSentByMe()
            );
            message.setLiked(entity.isLiked());
            messages.add(message);
        }
        return messages;
    }
    
    /**
     * 将业务模型转换为数据库实体
     * @param message 业务模型
     * @return 数据库实体
     */
    private ChatMessageEntity convertMessageToEntity(ChatMessage message) {
        return new ChatMessageEntity(
            message.getUsername(),
            message.getContent(),
            message.getTimestamp(),
            message.getAvatarResId(),
            message.isSentByMe()
        );
    }
    
    /**
     * 加载默认消息
     */
    private void loadDefaultMessages() {
        List<ChatMessage> defaultMessages = new ArrayList<>();
        defaultMessages.add(new ChatMessage("小美", "今天天气真好呢！", "10:30", android.R.drawable.ic_dialog_email, false));
        defaultMessages.add(new ChatMessage("我", "是啊，要不要出去走走？", "10:32", android.R.drawable.ic_dialog_info, true));
        defaultMessages.add(new ChatMessage("小美", "好主意！去哪里呢？", "10:33", android.R.drawable.ic_dialog_email, false));
        allMessagesLiveData.postValue(defaultMessages);
    }
    
    /**
     * 创建默认消息实体
     * @return 默认消息实体列表
     */
    private List<ChatMessageEntity> createDefaultMessages() {
        List<ChatMessageEntity> messages = new ArrayList<>();
        messages.add(new ChatMessageEntity("小美", "今天天气真好呢！", "10:30", android.R.drawable.ic_dialog_email, false));
        messages.add(new ChatMessageEntity("我", "是啊，要不要出去走走？", "10:32", android.R.drawable.ic_dialog_info, true));
        messages.add(new ChatMessageEntity("小美", "好主意！去哪里呢？", "10:33", android.R.drawable.ic_dialog_email, false));
        return messages;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
    }
    
    // ==================== 回调接口 ====================
    
    /**
     * 插入操作回调接口
     */
    public interface InsertCallback {
        void onSuccess(long id);
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
}