package com.example.couplecredit.service;

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

    private final MutableLiveData<SyncStatus> syncStatusLiveData = new MutableLiveData<>();
    private final MutableLiveData<SyncProgress> syncProgressLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> syncErrorLiveData = new MutableLiveData<>();

    private int currentUserId;
    private long currentRelationshipId;

    public ChatSyncService(ChatMessageDao localDao, CloudChatRepository cloudRepository) {
        this.localDao = localDao;
        this.cloudRepository = cloudRepository;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void setCurrentUserInfo(int userId, long relationshipId) {
        this.currentUserId = userId;
        this.currentRelationshipId = relationshipId;
    }

    public void performFullMigration(MigrationCallback callback) {
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

                    List<ChatMessageEntity> localMessages = localDao.getAllMessages();
                    if (localMessages.isEmpty()) {
                        Log.i(TAG, "本地没有消息需要迁移");
                        syncStatusLiveData.postValue(SyncStatus.COMPLETED);
                        if (callback != null) callback.onSuccess(0);
                        return;
                    }

                    int totalCount = localMessages.size();
                    AtomicInteger successCount = new AtomicInteger(0);
                    AtomicInteger failureCount = new AtomicInteger(0);

                    syncProgressLiveData.postValue(new SyncProgress(0, totalCount, "开始迁移消息..."));

                    for (ChatMessageEntity entity : localMessages) {
                        uploadMessageToCloud(entity, new SingleMessageSyncCallback() {
                            @Override
                            public void onSuccess() {
                                int completed = successCount.incrementAndGet();
                                syncProgressLiveData.postValue(
                                        new SyncProgress(completed, totalCount,
                                                String.format("已迁移 %d/%d 条消息", completed, totalCount))
                                );
                                if (completed + failureCount.get() == totalCount) {
                                    finalizeMigration(successCount.get(), failureCount.get(), callback);
                                }
                            }

                            @Override
                            public void onError(String error) {
                                int failed = failureCount.incrementAndGet();
                                Log.e(TAG, "迁移消息失败: " + error);
                                if (successCount.get() + failed == totalCount) {
                                    finalizeMigration(successCount.get(), failed, callback);
                                }
                            }
                        });

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

    public void performIncrementalSync(SyncCallback callback) {
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

                    cloudRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
                        @Override
                        public void onSuccess(List<ChatMessage> cloudMessages) {
                            if (executorService.isShutdown() || executorService.isTerminated()) {
                                Log.w(TAG, "ExecutorService已关闭，跳过同步任务");
                                return;
                            }

                            try {
                                executorService.execute(() -> {
                                    try {
                                        List<ChatMessageEntity> localMessages = localDao.getAllMessages();

                                        List<ChatMessageEntity> cloudEntities = new ArrayList<>();
                                        for (ChatMessage message : cloudMessages) {
                                            ChatMessageEntity entity = new ChatMessageEntity();
                                            entity.setUsername(message.getUsername());
                                            entity.setContent(message.getContent());
                                            entity.setTimestamp(message.getTimestamp());
                                            entity.setAvatarResId(message.getAvatarResId());
                                            entity.setLiked(message.isLiked());
                                            entity.setSentByMe(message.isSentByMe());
                                            entity.setCloudMessageId(message.getCloudMessageId());
                                            cloudEntities.add(entity);
                                        }

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

    private SyncResult performBidirectionalSync(List<ChatMessageEntity> localMessages, List<ChatMessageEntity> cloudMessages) {
        SyncResult result = new SyncResult();

        java.util.Map<Long, ChatMessageEntity> localCloudIdMap = new java.util.HashMap<>();
        java.util.Map<Long, ChatMessageEntity> cloudIdMap = new java.util.HashMap<>();
        java.util.Map<String, ChatMessageEntity> localContentMap = new java.util.HashMap<>();
        java.util.Map<String, ChatMessageEntity> cloudContentMap = new java.util.HashMap<>();

        for (ChatMessageEntity msg : localMessages) {
            if (msg.getCloudMessageId() != null && msg.getCloudMessageId() > 0) {
                localCloudIdMap.put(msg.getCloudMessageId(), msg);
            } else {
                String key = msg.getContent() + "_" + msg.getTimestamp();
                localContentMap.put(key, msg);
            }
        }

        for (ChatMessageEntity msg : cloudMessages) {
            if (msg.getCloudMessageId() != null && msg.getCloudMessageId() > 0) {
                cloudIdMap.put(msg.getCloudMessageId(), msg);
            }
            String key = msg.getContent() + "_" + msg.getTimestamp();
            cloudContentMap.put(key, msg);
        }

        for (ChatMessageEntity localMsg : localMessages) {
            if (localMsg.getCloudMessageId() == null || localMsg.getCloudMessageId() == 0) {
                String contentKey = localMsg.getContent() + "_" + localMsg.getTimestamp();
                if (!cloudContentMap.containsKey(contentKey)) {
                    uploadMessageToCloud(localMsg);
                    result.uploadedCount++;
                }
            }
        }

        for (ChatMessageEntity cloudMsg : cloudMessages) {
            if (cloudMsg.getCloudMessageId() == null || cloudMsg.getCloudMessageId() == 0) {
                continue;
            }
            if (!localCloudIdMap.containsKey(cloudMsg.getCloudMessageId())) {
                localDao.insertMessage(cloudMsg);
                result.downloadedCount++;
            } else {
                ChatMessageEntity localMsg = localCloudIdMap.get(cloudMsg.getCloudMessageId());
                if (localMsg != null && localMsg.isLiked() != cloudMsg.isLiked()) {
                    localMsg.setLiked(cloudMsg.isLiked());
                    localDao.updateMessage(localMsg);
                    result.conflictCount++;
                }
            }
        }

        return result;
    }

    private void uploadMessageToCloud(ChatMessageEntity entity) {
        uploadMessageToCloud(entity, null);
    }

    private void uploadMessageToCloud(ChatMessageEntity entity, SingleMessageSyncCallback callback) {
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
                    public void onSuccess(long cloudMessageId) {
                        Log.d(TAG, "消息上传成功，云端ID: " + cloudMessageId);
                        entity.setCloudMessageId(cloudMessageId);
                        entity.setSyncStatus(1);
                        try {
                            localDao.updateMessage(entity);
                        } catch (Exception ignore) {
                        }
                        Log.d(TAG, "已更新本地消息的云端ID: " + cloudMessageId + ", 本地ID: " + entity.getId());
                        if (callback != null) callback.onSuccess();
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "消息上传失败: " + e.getMessage());
                        if (callback != null) callback.onError(e.getMessage());
                    }
                }
        );
    }

    public void uploadOfflineMessage(ChatMessage message, SingleMessageSyncCallback callback) {
        if (executorService.isShutdown() || executorService.isTerminated()) {
            if (callback != null) callback.onError("服务已关闭");
            return;
        }

        try {
            executorService.execute(() -> {
                try {
                    ChatMessageEntity entity = new ChatMessageEntity();
                    entity.setContent(message.getContent());
                    entity.setTimestamp(message.getTimestamp());
                    entity.setLiked(message.isLiked());
                    entity.setSentByMe(message.isSentByMe());
                    uploadMessageToCloud(entity, callback);
                } catch (Exception e) {
                    Log.e(TAG, "上传离线消息失败", e);
                    if (callback != null) callback.onError(e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (callback != null) callback.onError("服务已关闭");
        }
    }

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

    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    public LiveData<SyncStatus> getSyncStatusLiveData() {
        return syncStatusLiveData;
    }

    public LiveData<SyncProgress> getSyncProgressLiveData() {
        return syncProgressLiveData;
    }

    public LiveData<String> getSyncErrorLiveData() {
        return syncErrorLiveData;
    }

    public enum SyncStatus {
        IDLE,
        MIGRATING,
        SYNCING,
        COMPLETED,
        PARTIAL_SUCCESS,
        ERROR
    }

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

    public static class SyncResult {
        public int uploadedCount = 0;
        public int downloadedCount = 0;
        public int conflictCount = 0;
    }

    public interface MigrationCallback {
        void onSuccess(int migratedCount);
        void onPartialSuccess(int successCount, int failureCount);
        void onError(String error);
    }

    public interface SyncCallback {
        void onSuccess(SyncResult result);
        void onError(String error);
    }

    public interface SingleMessageSyncCallback {
        void onSuccess();
        void onError(String error);
    }
}
