package com.example.couplecredit.repository;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
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
 * 聊天数据仓库类 - 增强版
 * 负责管理聊天消息的数据访问，包括本地数据库操作和云端数据库同步
 * 实现MVVM架构中的Repository层，为ViewModel提供统一的数据接口
 * 
 * 新增功能：
 * - 云端数据库同步
 * - 离线缓存机制
 * - 网络状态检测
 * - 数据一致性保证
 */
public class ChatRepository {
    
    private static final String TAG = "ChatRepository";
    
    // ==================== 成员变量 ====================
    
    private final ChatMessageDao chatMessageDao;
    private final CloudChatRepository cloudChatRepository;
    private final ExecutorService databaseExecutor;
    private final MutableLiveData<List<ChatMessage>> allMessagesLiveData;
    private final MutableLiveData<List<ChatMessage>> searchResultsLiveData;
    private final Context context;
    
    // 同步状态管理
    private final MutableLiveData<Boolean> syncStatusLiveData;
    private final MutableLiveData<String> syncErrorLiveData;
    private boolean isCloudSyncEnabled = true; // 是否启用云端同步
    private int currentUserId = 1; // 当前用户ID，应从用户会话获取
    
    // ==================== 构造函数 ====================
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public ChatRepository(Context context) {
        this.context = context;
        ChatDatabase database = ChatDatabase.getInstance(context);
        chatMessageDao = database.chatMessageDao();
        cloudChatRepository = new CloudChatRepository(context);
        databaseExecutor = Executors.newFixedThreadPool(4);
        allMessagesLiveData = new MutableLiveData<>();
        searchResultsLiveData = new MutableLiveData<>();
        syncStatusLiveData = new MutableLiveData<>();
        syncErrorLiveData = new MutableLiveData<>();
        
        // 初始化时测试云端连接
        testCloudConnection();
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
     * 获取同步状态的LiveData
     * @return 同步状态LiveData
     */
    public LiveData<Boolean> getSyncStatus() {
        return syncStatusLiveData;
    }
    
    /**
     * 获取同步错误信息的LiveData
     * @return 同步错误信息LiveData
     */
    public LiveData<String> getSyncError() {
        return syncErrorLiveData;
    }
    
    /**
     * 从数据库加载所有消息（优先从云端加载，失败时使用本地数据）
     */
    public void loadAllMessages() {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法加载消息");
            return;
        }
        
        if (isNetworkAvailable() && isCloudSyncEnabled) {
            // 优先从云端加载
            loadMessagesFromCloud();
        } else {
            // 网络不可用或云端同步关闭时，从本地加载
            loadMessagesFromLocal();
        }
    }
    
    /**
     * 插入新消息（同时保存到本地和云端）
     * @param message 要插入的消息
     * @param callback 插入完成后的回调
     */
    public void insertMessage(ChatMessage message, InsertCallback callback) {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法插入消息");
            if (callback != null) {
                callback.onError(new Exception("DatabaseExecutor已关闭"));
            }
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                // 1. 先保存到本地数据库
                ChatMessageEntity entity = convertMessageToEntity(message);
                long localId = chatMessageDao.insertMessage(entity);
                Log.d(TAG, "消息已保存到本地数据库，ID: " + localId);
                
                // 2. 立即更新UI
                loadMessagesFromLocal();
                
                // 3. 如果网络可用且云端同步开启，同步到云端
                if (isNetworkAvailable() && isCloudSyncEnabled) {
                    syncMessageToCloud(message, new CloudSyncCallback() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "消息已同步到云端");
                            syncStatusLiveData.postValue(true);
                            if (callback != null) {
                                callback.onSuccess(localId);
                            }
                        }
                        
                        @Override
                        public void onError(Exception e) {
                            Log.w(TAG, "消息同步到云端失败，但本地保存成功", e);
                            syncStatusLiveData.postValue(false);
                            syncErrorLiveData.postValue("云端同步失败: " + e.getMessage());
                            // 即使云端同步失败，本地保存成功也算成功
                            if (callback != null) {
                                callback.onSuccess(localId);
                            }
                        }
                    });
                } else {
                    Log.d(TAG, "网络不可用或云端同步关闭，仅保存到本地");
                    if (callback != null) {
                        callback.onSuccess(localId);
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "插入消息失败", e);
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
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法更新消息");
            if (callback != null) {
                callback.onError(new Exception("DatabaseExecutor已关闭"));
            }
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                // 1. 更新本地数据库
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                for (ChatMessageEntity entity : entities) {
                    if (entity.getContent().equals(message.getContent()) && 
                        entity.getTimestamp().equals(message.getTimestamp())) {
                        entity.setLiked(message.isLiked());
                        chatMessageDao.updateMessage(entity);
                        break;
                    }
                }
                
                // 2. 立即更新UI
                loadMessagesFromLocal();
                
                // 3. 同步到云端
                if (isNetworkAvailable() && isCloudSyncEnabled) {
                    cloudChatRepository.updateMessageLikeStatus(
                        message.getContent(), 
                        message.getTimestamp(), 
                        message.isLiked(),
                        new CloudChatRepository.UpdateCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "消息更新已同步到云端");
                                syncStatusLiveData.postValue(true);
                            }
                            
                            @Override
                            public void onError(Exception e) {
                                Log.w(TAG, "消息更新同步到云端失败", e);
                                syncStatusLiveData.postValue(false);
                                syncErrorLiveData.postValue("更新同步失败: " + e.getMessage());
                            }
                        }
                    );
                }
                
                if (callback != null) {
                    callback.onSuccess();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "更新消息失败", e);
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
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法删除消息");
            if (callback != null) {
                callback.onError(new Exception("DatabaseExecutor已关闭"));
            }
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                // 1. 从本地数据库删除
                chatMessageDao.deleteByContentAndTimestamp(message.getContent(), message.getTimestamp());
                
                // 2. 立即更新UI
                loadMessagesFromLocal();
                
                // 3. 同步到云端（软删除）
                if (isNetworkAvailable() && isCloudSyncEnabled) {
                    cloudChatRepository.deleteMessage(
                        message.getContent(),
                        message.getTimestamp(),
                        new CloudChatRepository.DeleteCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "消息删除已同步到云端");
                                syncStatusLiveData.postValue(true);
                            }
                            
                            @Override
                            public void onError(Exception e) {
                                Log.w(TAG, "消息删除同步到云端失败", e);
                                syncStatusLiveData.postValue(false);
                                syncErrorLiveData.postValue("删除同步失败: " + e.getMessage());
                            }
                        }
                    );
                }
                
                if (callback != null) {
                    callback.onSuccess();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "删除消息失败", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 搜索消息（优先从云端搜索）
     * @param keyword 搜索关键词
     */
    public void searchMessages(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // 如果搜索关键词为空，返回所有消息
            searchResultsLiveData.postValue(allMessagesLiveData.getValue());
            return;
        }
        
        if (isNetworkAvailable() && isCloudSyncEnabled) {
            // 从云端搜索
            cloudChatRepository.searchMessages(keyword.trim(), new CloudChatRepository.QueryCallback() {
                @Override
                public void onSuccess(List<ChatMessage> messages) {
                    Log.d(TAG, "云端搜索成功，找到 " + messages.size() + " 条消息");
                    searchResultsLiveData.postValue(messages);
                }
                
                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "云端搜索失败，使用本地搜索", e);
                    searchMessagesFromLocal(keyword.trim());
                }
            });
        } else {
            // 从本地搜索
            searchMessagesFromLocal(keyword.trim());
        }
    }
    
    /**
     * 手动同步数据到云端
     * @param callback 同步完成回调
     */
    public void syncToCloud(SyncCallback callback) {
        if (!isNetworkAvailable()) {
            if (callback != null) {
                callback.onError(new Exception("网络不可用"));
            }
            return;
        }
        
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法同步到云端");
            if (callback != null) {
                callback.onError(new Exception("DatabaseExecutor已关闭"));
            }
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                syncStatusLiveData.postValue(true);
                
                // 获取本地所有消息
                List<ChatMessageEntity> localEntities = chatMessageDao.getAllMessages();
                List<ChatMessage> localMessages = convertEntitiesToMessages(localEntities);
                
                Log.d(TAG, "开始同步 " + localMessages.size() + " 条本地消息到云端");
                
                // 逐条同步到云端
                int successCount = 0;
                int errorCount = 0;
                
                for (ChatMessage message : localMessages) {
                    try {
                        // 这里使用同步方式插入（需要修改CloudChatRepository添加同步方法）
                        syncMessageToCloudSync(message);
                        successCount++;
                    } catch (Exception e) {
                        Log.w(TAG, "同步消息失败: " + message.getContent(), e);
                        errorCount++;
                    }
                }
                
                Log.d(TAG, "同步完成：成功 " + successCount + " 条，失败 " + errorCount + " 条");
                
                syncStatusLiveData.postValue(false);
                
                if (callback != null) {
                    if (errorCount == 0) {
                        callback.onSuccess("同步成功：" + successCount + " 条消息");
                    } else {
                        callback.onError(new Exception("部分同步失败：成功 " + successCount + " 条，失败 " + errorCount + " 条"));
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "同步过程中发生错误", e);
                syncStatusLiveData.postValue(false);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 从云端拉取最新数据
     * @param callback 拉取完成回调
     */
    public void pullFromCloud(SyncCallback callback) {
        if (!isNetworkAvailable()) {
            if (callback != null) {
                callback.onError(new Exception("网络不可用"));
            }
            return;
        }
        
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> cloudMessages) {
                databaseExecutor.execute(() -> {
                    try {
                        // 清空本地数据库
                        chatMessageDao.deleteAllMessages();
                        
                        // 将云端数据保存到本地
                        List<ChatMessageEntity> entities = new ArrayList<>();
                        for (ChatMessage message : cloudMessages) {
                            entities.add(convertMessageToEntity(message));
                        }
                        
                        if (!entities.isEmpty()) {
                            chatMessageDao.insertMessages(entities);
                        }
                        
                        // 更新UI
                        loadMessagesFromLocal();
                        
                        Log.d(TAG, "从云端拉取了 " + cloudMessages.size() + " 条消息");
                        
                        if (callback != null) {
                            callback.onSuccess("拉取成功：" + cloudMessages.size() + " 条消息");
                        }
                        
                    } catch (Exception e) {
                        Log.e(TAG, "保存云端数据到本地失败", e);
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                });
            }
            
            @Override
            public void onError(Exception e) {
                Log.e(TAG, "从云端拉取数据失败", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 设置云端同步开关
     * @param enabled 是否启用云端同步
     */
    public void setCloudSyncEnabled(boolean enabled) {
        this.isCloudSyncEnabled = enabled;
        Log.d(TAG, "云端同步已" + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 设置当前用户ID
     * @param userId 用户ID
     */
    public void setCurrentUserId(int userId) {
        this.currentUserId = userId;
        // 通知CloudChatRepository重新初始化用户信息
        cloudChatRepository.initializeUserInfo();
    }
    
    /**
     * 保存默认消息到数据库
     */
    public void saveDefaultMessages() {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法保存默认消息");
            return;
        }
        
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
     * 从本地数据库加载消息
     */
    private void loadMessagesFromLocal() {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法从本地加载消息");
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                allMessagesLiveData.postValue(messages);
                Log.d(TAG, "从本地加载了 " + messages.size() + " 条消息");
            } catch (Exception e) {
                Log.e(TAG, "从本地加载消息失败", e);
                // 如果本地数据库为空或出错，加载默认消息
                loadDefaultMessages();
            }
        });
    }
    
    /**
     * 从云端数据库加载消息
     */
    private void loadMessagesFromCloud() {
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> messages) {
                Log.d(TAG, "从云端加载了 " + messages.size() + " 条消息");
                allMessagesLiveData.postValue(messages);
                syncStatusLiveData.postValue(true);
                
                // 同时更新本地缓存
                updateLocalCache(messages);
            }
            
            @Override
            public void onError(Exception e) {
                Log.w(TAG, "从云端加载消息失败，使用本地数据", e);
                syncStatusLiveData.postValue(false);
                syncErrorLiveData.postValue("云端加载失败: " + e.getMessage());
                // 云端加载失败时，使用本地数据
                loadMessagesFromLocal();
            }
        });
    }
    
    /**
     * 从本地搜索消息
     * @param keyword 搜索关键词
     */
    private void searchMessagesFromLocal(String keyword) {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法搜索消息");
            searchResultsLiveData.postValue(new ArrayList<>());
            return;
        }
        
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.searchMessages(keyword);
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                searchResultsLiveData.postValue(messages);
                Log.d(TAG, "本地搜索找到 " + messages.size() + " 条消息");
            } catch (Exception e) {
                Log.e(TAG, "本地搜索失败", e);
                searchResultsLiveData.postValue(new ArrayList<>());
            }
        });
    }
    
    /**
     * 同步单条消息到云端
     * @param message 要同步的消息
     * @param callback 同步回调
     */
    private void syncMessageToCloud(ChatMessage message, CloudSyncCallback callback) {
        cloudChatRepository.insertMessage(message, currentUserId, new CloudChatRepository.InsertCallback() {
            @Override
            public void onSuccess(long messageId) {
                if (callback != null) {
                    callback.onSuccess();
                }
            }
            
            @Override
            public void onError(Exception e) {
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }
    
    /**
     * 同步方式插入消息到云端（用于批量同步）
     * @param message 要同步的消息
     * @throws Exception 同步异常
     */
    private void syncMessageToCloudSync(ChatMessage message) throws Exception {
        // 这里需要实现同步版本的云端插入
        // 为了简化，暂时使用异步版本
        final Exception[] syncException = {null};
        final boolean[] syncCompleted = {false};
        
        syncMessageToCloud(message, new CloudSyncCallback() {
            @Override
            public void onSuccess() {
                syncCompleted[0] = true;
            }
            
            @Override
            public void onError(Exception e) {
                syncException[0] = e;
                syncCompleted[0] = true;
            }
        });
        
        // 等待同步完成（简单的同步等待，生产环境建议使用更好的同步机制）
        while (!syncCompleted[0]) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("同步被中断", e);
            }
        }
        
        if (syncException[0] != null) {
            throw syncException[0];
        }
    }
    
    /**
     * 更新本地缓存
     * @param cloudMessages 云端消息列表
     */
    private void updateLocalCache(List<ChatMessage> cloudMessages) {
        // 检查ExecutorService状态
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            Log.w(TAG, "DatabaseExecutor已关闭，无法更新本地缓存");
            return;
        }
        
        databaseExecutor.execute(() -> {
            ChatDatabase database = null;
            try {
                // 获取数据库实例
                database = ChatDatabase.getInstance(context);
                
                // 开始事务
                database.runInTransaction(() -> {
                    try {
                        // 清空本地数据库
                        chatMessageDao.deleteAllMessages();
                        
                        // 保存云端数据到本地
                        List<ChatMessageEntity> entities = new ArrayList<>();
                        for (ChatMessage message : cloudMessages) {
                            entities.add(convertMessageToEntity(message));
                        }
                        
                        if (!entities.isEmpty()) {
                            chatMessageDao.insertMessages(entities);
                        }
                        
                        Log.d(TAG, "本地缓存已更新，共 " + entities.size() + " 条消息");
                        
                    } catch (Exception e) {
                        Log.e(TAG, "事务内更新本地缓存失败", e);
                        throw new RuntimeException(e); // 回滚事务
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "更新本地缓存失败", e);
                
                // 如果是数据库相关错误，尝试重新初始化数据库
                if (e.getMessage() != null && 
                    (e.getMessage().contains("SQLite") || 
                     e.getMessage().contains("database") ||
                     e.getMessage().contains("Room"))) {
                    Log.w(TAG, "检测到数据库错误，尝试重新初始化");
                    try {
                        ChatDatabase.destroyInstance();
                        // 重新获取数据库实例将触发迁移
                        ChatDatabase.getInstance(context);
                    } catch (Exception reinitException) {
                        Log.e(TAG, "重新初始化数据库失败", reinitException);
                    }
                }
            }
        });
    }
    
    /**
     * 测试云端连接
     */
    private void testCloudConnection() {
        if (isNetworkAvailable()) {
            cloudChatRepository.testConnection(new CloudChatRepository.ConnectionTestCallback() {
                @Override
                public void onSuccess(String message) {
                    Log.d(TAG, "云端连接测试成功: " + message);
                    syncStatusLiveData.postValue(true);
                }
                
                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "云端连接测试失败", e);
                    syncStatusLiveData.postValue(false);
                    syncErrorLiveData.postValue("云端连接失败: " + e.getMessage());
                }
            });
        } else {
            Log.d(TAG, "网络不可用，跳过云端连接测试");
            syncStatusLiveData.postValue(false);
        }
    }
    
    /**
     * 检查网络是否可用
     * @return 网络是否可用
     */
    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态失败", e);
        }
        return false;
    }
    
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
        if (cloudChatRepository != null) {
            cloudChatRepository.cleanup();
        }
    }
    
    // ==================== 新增：数据访问方法 ====================
    
    /**
     * 获取ChatMessageDao实例
     * @return ChatMessageDao实例
     */
    public ChatMessageDao getChatMessageDao() {
        return chatMessageDao;
    }
    
    /**
     * 获取CloudChatRepository实例
     * @return CloudChatRepository实例
     */
    public CloudChatRepository getCloudRepository() {
        return cloudChatRepository;
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
    
    /**
     * 同步操作回调接口
     */
    public interface SyncCallback {
        void onSuccess(String message);
        void onError(Exception e);
    }
    
    /**
     * 云端同步回调接口
     */
    public interface CloudSyncCallback {
        void onSuccess();
        void onError(Exception e);
    }
}