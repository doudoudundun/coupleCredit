package com.example.couplecredit.service;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.database.ChatMessageDao;
import com.example.couplecredit.database.ChatMessageEntity;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.repository.CloudChatRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聊天数据同步服务类
 * 负责处理本地数据库到云端数据库的迁移和双向同步
 */
public class ChatSyncService {
    private static final String TAG = "ChatSyncService";
    
    private final ChatMessageDao localDao;
    private final CloudChatRepository cloudRepository;
    private final ExecutorService executorService;
    
    // 同步状态监听
    private final MutableLiveData<SyncStatus> syncStatusLiveData = new MutableLiveData<>();
    private final MutableLiveData<SyncProgress> syncProgressLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> syncErrorLiveData = new MutableLiveData<>();
    
    // 当前用户ID和关系ID
    private int currentUserId;
    private long currentRelationshipId;
    
    public ChatSyncService(ChatMessageDao localDao, CloudChatRepository cloudRepository) {
        this.localDao = localDao;
        this.cloudRepository = cloudRepository;
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    /**
     * 设置当前用户信息
     */
    public void setCurrentUserInfo(int userId, long relationshipId) {
        this.currentUserId = userId;
        this.currentRelationshipId = relationshipId;
    }
    
    /**
     * 执行完整的数据迁移（从本地到云端）
     */
    public void performFullMigration(MigrationCallback callback) {
        // 检查ExecutorService状态
        if (executorService.isShutdown() || executorService.isTerminated()) {
            Log.w(TAG, "ExecutorService已关闭，跳过迁移任务");
            if (callback != null) callback.onError("服务已关闭");
            return;
        }
        
        try {
            executorService.execute(() -> {
            try {
                syncStatusLiveData.postValue(SyncStatus.MIGRATING);
                Log.i(TAG, "开始执行完整数据迁移");
                
                // 1. 获取所有本地消息
                List<ChatMessageEntity> localMessages = localDao.getAllMessages();
                if (localMessages.isEmpty()) {
                    Log.i(TAG, "本地没有消息需要迁移");
                    syncStatusLiveData.postValue(SyncStatus.COMPLETED);
                    if (callback != null) callback.onSuccess(0);
                    return;
                }
                
                // 2. 批量上传到云端
                int totalCount = localMessages.size();
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failureCount = new AtomicInteger(0);
                
                syncProgressLiveData.postValue(new SyncProgress(0, totalCount, "开始迁移消息..."));
                
                for (int i = 0; i < localMessages.size(); i++) {
                    ChatMessageEntity entity = localMessages.get(i);
                    final int currentIndex = i;
                    
                    // 转换为云端格式并插入
                    cloudRepository.insertMessage(
                        currentRelationshipId,
                        currentUserId,
                        entity.getContent(),
                        "text", // 默认消息类型
                        entity.getTimestamp(),
                        null, // avatar_url
                        entity.isLiked(),
                        new CloudChatRepository.InsertCallback() {
                            @Override
                            public void onSuccess(long messageId) {
                                int completed = successCount.incrementAndGet();
                                syncProgressLiveData.postValue(
                                    new SyncProgress(completed, totalCount, 
                                        String.format("已迁移 %d/%d 条消息", completed, totalCount))
                                );
                                
                                // 检查是否全部完成
                                if (completed + failureCount.get() == totalCount) {
                                    finalizeMigration(successCount.get(), failureCount.get(), callback);
                                }
                            }
                            
                            @Override
                            public void onError(Exception e) {
                                int failed = failureCount.incrementAndGet();
                                Log.e(TAG, "迁移消息失败: " + e.getMessage());
                                
                                // 检查是否全部完成
                                if (successCount.get() + failed == totalCount) {
                                    finalizeMigration(successCount.get(), failed, callback);
                                }
                            }
                        }
                    );
                    
                    // 避免过快请求
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "数据迁移失败", e);
                syncStatusLiveData.postValue(SyncStatus.ERROR);
                syncErrorLiveData.postValue("迁移失败: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "ExecutorService已关闭，无法执行迁移任务: " + e.getMessage());
            if (callback != null) callback.onError("服务已关闭");
        } catch (Exception e) {
            Log.e(TAG, "提交迁移任务失败: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }
    
    /**
     * 执行增量同步（双向）
     */
    public void performIncrementalSync(SyncCallback callback) {
        // 检查ExecutorService状态
        if (executorService.isShutdown() || executorService.isTerminated()) {
            Log.w(TAG, "ExecutorService已关闭，跳过同步任务");
            if (callback != null) callback.onError("服务已关闭");
            return;
        }
        
        try {
            executorService.execute(() -> {
            try {
                syncStatusLiveData.postValue(SyncStatus.SYNCING);
                Log.i(TAG, "开始执行增量同步");
                
                // 1. 从云端获取最新消息
                cloudRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
                    @Override
                    public void onSuccess(List<ChatMessage> cloudMessages) {
                        // 检查ExecutorService状态
                        if (executorService.isShutdown() || executorService.isTerminated()) {
                            Log.w(TAG, "ExecutorService已关闭，跳过同步任务");
                            return;
                        }
                        
                        try {
                            executorService.execute(() -> {
                            try {
                                // 2. 获取本地消息
                                List<ChatMessageEntity> localMessages = localDao.getAllMessages();
                                
                                // 3. 将CloudMessage转换为Entity进行比较
                                List<ChatMessageEntity> cloudEntities = new ArrayList<>();
                                for (ChatMessage message : cloudMessages) {
                                    ChatMessageEntity entity = new ChatMessageEntity();
                                    entity.setUsername(message.getUsername());
                                    entity.setContent(message.getContent());
                                    entity.setTimestamp(message.getTimestamp());
                                    entity.setAvatarResId(message.getAvatarResId());
                                    entity.setLiked(message.isLiked());
                                    entity.setSentByMe(message.isSentByMe());
                                    cloudEntities.add(entity);
                                }
                                
                                // 4. 执行双向同步
                                SyncResult result = performBidirectionalSync(localMessages, cloudEntities);
                                
                                syncStatusLiveData.postValue(SyncStatus.COMPLETED);
                                Log.i(TAG, String.format("增量同步完成: 上传%d条，下载%d条，冲突%d条", 
                                    result.uploadedCount, result.downloadedCount, result.conflictCount));
                                
                                if (callback != null) callback.onSuccess(result);
                                
                            } catch (Exception e) {
                                Log.e(TAG, "增量同步失败", e);
                                syncStatusLiveData.postValue(SyncStatus.ERROR);
                                syncErrorLiveData.postValue("同步失败: " + e.getMessage());
                                if (callback != null) callback.onError(e.getMessage());
                            }
                        });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            Log.w(TAG, "ExecutorService已关闭，无法执行同步任务: " + e.getMessage());
                        } catch (Exception e) {
                            Log.e(TAG, "提交同步任务失败: " + e.getMessage(), e);
                        }
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "获取云端消息失败: " + e.getMessage());
                        syncStatusLiveData.postValue(SyncStatus.ERROR);
                        syncErrorLiveData.postValue("同步失败: " + e.getMessage());
                        if (callback != null) callback.onError(e.getMessage());
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "增量同步失败", e);
                syncStatusLiveData.postValue(SyncStatus.ERROR);
                syncErrorLiveData.postValue("同步失败: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "ExecutorService已关闭，无法执行同步任务: " + e.getMessage());
            if (callback != null) callback.onError("服务已关闭");
        } catch (Exception e) {
            Log.e(TAG, "提交同步任务失败: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }
    
    /**
     * 执行双向同步逻辑
     */
    private SyncResult performBidirectionalSync(List<ChatMessageEntity> localMessages, 
                                               List<ChatMessageEntity> cloudMessages) {
        SyncResult result = new SyncResult();
        
        // 创建时间戳映射用于快速查找
        java.util.Map<String, ChatMessageEntity> localMap = new java.util.HashMap<>();
        java.util.Map<String, ChatMessageEntity> cloudMap = new java.util.HashMap<>();
        
        for (ChatMessageEntity msg : localMessages) {
            String key = msg.getContent() + "_" + msg.getTimestamp();
            localMap.put(key, msg);
        }
        
        for (ChatMessageEntity msg : cloudMessages) {
            String key = msg.getContent() + "_" + msg.getTimestamp();
            cloudMap.put(key, msg);
        }
        
        // 1. 找出需要上传到云端的本地消息
        for (ChatMessageEntity localMsg : localMessages) {
            String key = localMsg.getContent() + "_" + localMsg.getTimestamp();
            if (!cloudMap.containsKey(key)) {
                // 上传到云端
                uploadMessageToCloud(localMsg);
                result.uploadedCount++;
            }
        }
        
        // 2. 找出需要下载到本地的云端消息
        for (ChatMessageEntity cloudMsg : cloudMessages) {
            String key = cloudMsg.getContent() + "_" + cloudMsg.getTimestamp();
            if (!localMap.containsKey(key)) {
                // 下载到本地
                localDao.insertMessage(cloudMsg);
                result.downloadedCount++;
            } else {
                // 检查是否有冲突（如点赞状态不同）
                ChatMessageEntity localMsg = localMap.get(key);
                if (localMsg.isLiked() != cloudMsg.isLiked()) {
                    // 以云端数据为准，更新本地
                    localMsg.setLiked(cloudMsg.isLiked());
                    localDao.updateMessage(localMsg);
                    result.conflictCount++;
                }
            }
        }
        
        return result;
    }
    
    /**
     * 上传单条消息到云端
     */
    private void uploadMessageToCloud(ChatMessageEntity entity) {
        cloudRepository.insertMessage(
            currentRelationshipId,
            currentUserId,
            entity.getContent(),
            "text",
            entity.getTimestamp(),
            null,
            entity.isLiked(),
            new CloudChatRepository.InsertCallback() {
                @Override
                public void onSuccess(long messageId) {
                    Log.d(TAG, "消息上传成功: " + messageId);
                }
                
                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "消息上传失败: " + e.getMessage());
                }
            }
        );
    }
    
    /**
     * 完成迁移的最终处理
     */
    private void finalizeMigration(int successCount, int failureCount, MigrationCallback callback) {
        if (failureCount == 0) {
            syncStatusLiveData.postValue(SyncStatus.COMPLETED);
            Log.i(TAG, String.format("数据迁移完成: 成功迁移 %d 条消息", successCount));
            if (callback != null) callback.onSuccess(successCount);
        } else {
            syncStatusLiveData.postValue(SyncStatus.PARTIAL_SUCCESS);
            String message = String.format("数据迁移部分成功: 成功 %d 条，失败 %d 条", successCount, failureCount);
            Log.w(TAG, message);
            syncErrorLiveData.postValue(message);
            if (callback != null) callback.onPartialSuccess(successCount, failureCount);
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
    
    // Getter方法
    public LiveData<SyncStatus> getSyncStatusLiveData() {
        return syncStatusLiveData;
    }
    
    public LiveData<SyncProgress> getSyncProgressLiveData() {
        return syncProgressLiveData;
    }
    
    public LiveData<String> getSyncErrorLiveData() {
        return syncErrorLiveData;
    }
    
    // 内部类和接口定义
    
    /**
     * 同步状态枚举
     */
    public enum SyncStatus {
        IDLE,           // 空闲
        MIGRATING,      // 迁移中
        SYNCING,        // 同步中
        COMPLETED,      // 完成
        PARTIAL_SUCCESS,// 部分成功
        ERROR           // 错误
    }
    
    /**
     * 同步进度信息
     */
    public static class SyncProgress {
        public final int completed;
        public final int total;
        public final String message;
        
        public SyncProgress(int completed, int total, String message) {
            this.completed = completed;
            this.total = total;
            this.message = message;
        }
        
        public int getPercentage() {
            return total > 0 ? (completed * 100 / total) : 0;
        }
    }
    
    /**
     * 同步结果信息
     */
    public static class SyncResult {
        public int uploadedCount = 0;   // 上传到云端的消息数
        public int downloadedCount = 0; // 从云端下载的消息数
        public int conflictCount = 0;   // 冲突解决的消息数
    }
    
    /**
     * 迁移回调接口
     */
    public interface MigrationCallback {
        void onSuccess(int migratedCount);
        void onPartialSuccess(int successCount, int failureCount);
        void onError(String error);
    }
    
    /**
     * 同步回调接口
     */
    public interface SyncCallback {
        void onSuccess(SyncResult result);
        void onError(String error);
    }
}