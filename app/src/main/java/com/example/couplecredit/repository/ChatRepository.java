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
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ChatRepository {

    private static final String TAG = "ChatRepository";

    private final ChatMessageDao chatMessageDao;
    private final CloudChatRepository cloudChatRepository;
    private final ExecutorService databaseExecutor;
    private final MutableLiveData<List<ChatMessage>> allMessagesLiveData;
    private final MutableLiveData<List<ChatMessage>> searchResultsLiveData;
    private final Context context;

    private int currentUserId = -1;
    private volatile long currentRelationshipId = -1;
    private final AtomicLong relationshipGeneration = new AtomicLong();

    public ChatRepository(Context context) {
        this.context = context.getApplicationContext();
        ChatDatabase database = ChatDatabase.getInstance(context);
        chatMessageDao = database.chatMessageDao();
        cloudChatRepository = new CloudChatRepository(context);
        // 本地写入、同步状态更新和云端缓存刷新共享同一条队列，避免并发覆盖离线消息。
        databaseExecutor = Executors.newSingleThreadExecutor();
        allMessagesLiveData = new MutableLiveData<>();
        searchResultsLiveData = new MutableLiveData<>();

        initializeUserInfoFromManager();
    }

    public LiveData<List<ChatMessage>> getAllMessages() {
        return allMessagesLiveData;
    }

    public LiveData<List<ChatMessage>> getSearchResults() {
        return searchResultsLiveData;
    }

    public void loadAllMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;

        if (isNetworkAvailable()) {
            syncPendingMessages(this::loadMessagesFromCloud);
        } else {
            loadMessagesFromLocal();
        }
    }

    public void insertMessage(ChatMessage message, InsertCallback callback) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (callback != null) callback.onError(new Exception("DatabaseExecutor已关闭"));
            return;
        }
        if (message == null || currentRelationshipId <= 0
                || message.getRelationshipId() != currentRelationshipId) {
            if (callback != null) callback.onError(new Exception("当前情侣关系无效，请刷新后重试"));
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                if (message.getRelationshipId() != currentRelationshipId) {
                    if (callback != null) callback.onError(new Exception("情侣关系已变化，消息未发送"));
                    return;
                }
                ChatMessageEntity entity = convertMessageToEntity(message);
                long localId = chatMessageDao.insertMessage(entity);
                message.setId(localId);
                Log.d(TAG, "消息已保存到本地数据库，ID: " + localId);

                loadMessagesFromLocal();

                if (isNetworkAvailable()) {
                    syncMessageToCloud(message, new CloudSyncCallback() {
                        @Override
                        public void onSuccess() {
                            if (callback != null) callback.onSuccess(localId);
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.w(TAG, "消息同步到云端失败，但本地保存成功", e);
                            if (callback != null) callback.onSuccess(localId);
                        }
                    });
                } else {
                    if (callback != null) callback.onSuccess(localId);
                }

            } catch (Exception e) {
                Log.e(TAG, "插入消息失败", e);
                if (callback != null) callback.onError(e);
            }
        });
    }

    public void deleteMessage(ChatMessage message, DeleteCallback callback) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (callback != null) callback.onError(new Exception("DatabaseExecutor已关闭"));
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                if (currentRelationshipId <= 0
                        || message.getRelationshipId() != currentRelationshipId) {
                    if (callback != null) callback.onError(new Exception("无权删除其他关系的消息"));
                    return;
                }
                if (message.getId() > 0) {
                    chatMessageDao.deleteById(message.getId(), currentRelationshipId);
                } else if (message.getCloudMessageId() != null && message.getCloudMessageId() != 0) {
                    chatMessageDao.deleteByCloudId(message.getCloudMessageId(), currentRelationshipId);
                } else {
                    Log.e(TAG, "无法删除消息：缺少消息ID, content=" + message.getContent());
                    if (callback != null) callback.onError(new Exception("无法删除消息：缺少消息ID"));
                    return;
                }

                loadMessagesFromLocal();

                if (isNetworkAvailable()
                        && message.getCloudMessageId() != null && message.getCloudMessageId() > 0) {
                    long cloudMessageId = (message.getCloudMessageId() != null && message.getCloudMessageId() != 0)
                            ? message.getCloudMessageId() : 0;
                    cloudChatRepository.deleteMessage(cloudMessageId, message.getContent(),
                            message.getTimestamp(), new CloudChatRepository.DeleteCallback() {
                                @Override
                                public void onSuccess() {
                                }

                                @Override
                                public void onError(Exception e) {
                                    Log.w(TAG, "消息删除同步到云端失败", e);
                                }
                            });
                }

                if (callback != null) callback.onSuccess();

            } catch (Exception e) {
                Log.e(TAG, "删除消息失败", e);
                if (callback != null) callback.onError(e);
            }
        });
    }

    public void searchMessages(String keyword) {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) {
            searchResultsLiveData.postValue(new ArrayList<>());
            return;
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            searchResultsLiveData.postValue(filterMessagesForRelationship(
                    allMessagesLiveData.getValue(), relationshipId));
            return;
        }

        if (isNetworkAvailable()) {
            cloudChatRepository.searchMessages(keyword.trim(), new CloudChatRepository.QueryCallback() {
                @Override
                public void onSuccess(List<ChatMessage> messages) {
                    if (!isCurrentRelationship(relationshipId, generation)) return;
                    searchResultsLiveData.postValue(
                            filterMessagesForRelationship(messages, relationshipId));
                }

                @Override
                public void onError(Exception e) {
                    if (!isCurrentRelationship(relationshipId, generation)) return;
                    Log.w(TAG, "云端搜索失败，使用本地搜索", e);
                    searchMessagesFromLocal(keyword.trim());
                }
            });
        } else {
            searchMessagesFromLocal(keyword.trim());
        }
    }

    private void syncPendingMessages(Runnable onComplete) {
        if (!isNetworkAvailable()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        final long relationshipId = currentRelationshipId;
        if (relationshipId <= 0) {
            if (onComplete != null) onComplete.run();
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                syncPendingAt(chatMessageDao.getPendingMessages(relationshipId), 0, relationshipId, onComplete);
            } catch (Exception e) {
                Log.e(TAG, "同步过程中发生错误", e);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    private void syncPendingAt(List<ChatMessageEntity> pendingEntities, int index,
                               long relationshipId, Runnable onComplete) {
        if (index >= pendingEntities.size()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (relationshipId <= 0 || relationshipId != currentRelationshipId) {
            if (onComplete != null) onComplete.run();
            return;
        }

        ChatMessageEntity entity = pendingEntities.get(index);
        if (entity.getRelationshipId() != relationshipId) {
            syncPendingAt(pendingEntities, index + 1, relationshipId, onComplete);
            return;
        }
        ChatMessage message = convertEntitiesToMessages(java.util.Collections.singletonList(entity)).get(0);
        syncMessageToCloud(message, new CloudSyncCallback() {
            @Override
            public void onSuccess() {
                databaseExecutor.execute(() -> syncPendingAt(
                        pendingEntities, index + 1, relationshipId, onComplete));
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "同步消息失败: " + message.getContent(), e);
                databaseExecutor.execute(() -> {
                    try {
                        ChatMessageEntity failed = chatMessageDao.getMessageById(entity.getId(), relationshipId);
                        if (failed != null) {
                            failed.markSyncFailed();
                            chatMessageDao.updateMessage(failed);
                        }
                    } catch (Exception updateError) {
                        Log.w(TAG, "更新消息同步状态失败", updateError);
                    }
                    syncPendingAt(pendingEntities, index + 1, relationshipId, onComplete);
                });
            }
        });
    }

    private void initializeUserInfoFromManager() {
        if (UserInfoManager.isUserLoggedIn(context)) {
            int userId = UserInfoManager.getCurrentUserId(context);
            if (userId > 0) {
                this.currentUserId = userId;
            } else {
                this.currentUserId = -1;
            }
        } else {
            this.currentUserId = -1;
        }

        if (cloudChatRepository != null) {
            cloudChatRepository.initializeUserInfo();
        }
    }

    public void setCurrentUserId(int userId) {
        this.currentUserId = userId;
        cloudChatRepository.initializeUserInfo();
    }

    public void setCurrentRelationshipId(long relationshipId) {
        long normalizedRelationshipId = relationshipId > 0 ? relationshipId : -1;
        if (this.currentRelationshipId != normalizedRelationshipId) {
            this.currentRelationshipId = normalizedRelationshipId;
            relationshipGeneration.incrementAndGet();
            allMessagesLiveData.postValue(new ArrayList<>());
            searchResultsLiveData.postValue(new ArrayList<>());
        }
        cloudChatRepository.setCurrentRelationshipId((int) this.currentRelationshipId);
    }

    private boolean isCurrentRelationship(long relationshipId, long generation) {
        return relationshipId > 0
                && relationshipId == currentRelationshipId
                && generation == relationshipGeneration.get();
    }

    private void loadMessagesFromLocal() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) {
            allMessagesLiveData.postValue(new ArrayList<>());
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getMessagesForRelationship(relationshipId);
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                if (!isCurrentRelationship(relationshipId, generation)) return;
                allMessagesLiveData.postValue(messages);
            } catch (Exception e) {
                Log.e(TAG, "从本地加载消息失败", e);
                if (isCurrentRelationship(relationshipId, generation)) {
                    allMessagesLiveData.postValue(new ArrayList<>());
                }
            }
        });
    }

    private void loadMessagesFromCloud() {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) return;
        cloudChatRepository.getBatchChatData(new CloudChatRepository.BatchDataCallback() {
            @Override
            public void onSuccess(CloudChatRepository.BatchChatData batchData) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                updateLocalCache(relationshipId, generation, batchData.messages,
                        ChatRepository.this::loadMessagesFromLocal);
            }

            @Override
            public void onError(Exception e) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                Log.w(TAG, "从云端加载消息失败，使用本地数据", e);
                loadMessagesFromLocal();
            }
        });
    }

    private void loadNewerMessagesFromCloud() {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) return;
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> newMessages) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                if (newMessages == null || newMessages.isEmpty()) return;

                List<ChatMessage> allMessages = allMessagesLiveData.getValue();
                if (allMessages == null) allMessages = new ArrayList<>();

                List<ChatMessage> updatedMessages = filterMessagesForRelationship(
                        allMessages, relationshipId);
                for (ChatMessage newMsg : filterMessagesForRelationship(newMessages, relationshipId)) {
                    if (!updatedMessages.contains(newMsg)) {
                        updatedMessages.add(newMsg);
                    }
                }
                if (!isCurrentRelationship(relationshipId, generation)) return;
                allMessagesLiveData.postValue(updatedMessages);
            }

            @Override
            public void onError(Exception e) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                Log.w(TAG, "从云端加载新消息失败", e);
                loadNewerMessagesFromLocal();
            }
        });
    }

    private void loadOlderMessagesFromCloud() {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) return;
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> olderMessages) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                if (olderMessages == null || olderMessages.isEmpty()) return;

                List<ChatMessage> allMessages = allMessagesLiveData.getValue();
                if (allMessages == null) allMessages = new ArrayList<>();
                allMessages = filterMessagesForRelationship(allMessages, relationshipId);

                List<ChatMessage> updatedMessages = new ArrayList<>();
                for (ChatMessage olderMsg : filterMessagesForRelationship(olderMessages, relationshipId)) {
                    if (!allMessages.contains(olderMsg)) {
                        updatedMessages.add(olderMsg);
                    }
                }
                updatedMessages.addAll(allMessages);
                if (!isCurrentRelationship(relationshipId, generation)) return;
                allMessagesLiveData.postValue(updatedMessages);
            }

            @Override
            public void onError(Exception e) {
                if (!isCurrentRelationship(relationshipId, generation)) return;
                Log.w(TAG, "从云端加载历史消息失败", e);
                loadOlderMessagesFromLocal();
            }
        });
    }

    private void loadNewerMessagesFromLocal() {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) {
            allMessagesLiveData.postValue(new ArrayList<>());
            return;
        }
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getMessagesForRelationship(relationshipId);
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                List<ChatMessage> currentMessages = allMessagesLiveData.getValue();
                if (!isCurrentRelationship(relationshipId, generation)) return;
                if (currentMessages == null || messages.size() > currentMessages.size()) {
                    allMessagesLiveData.postValue(messages);
                }
            } catch (Exception e) {
                Log.e(TAG, "从本地加载新消息失败", e);
            }
        });
    }

    private void loadOlderMessagesFromLocal() {
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) {
            allMessagesLiveData.postValue(new ArrayList<>());
            return;
        }
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getMessagesForRelationship(relationshipId);
                if (!isCurrentRelationship(relationshipId, generation)) return;
                allMessagesLiveData.postValue(convertEntitiesToMessages(entities));
            } catch (Exception e) {
                Log.e(TAG, "从本地加载历史消息失败", e);
            }
        });
    }

    private void searchMessagesFromLocal(String keyword) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            searchResultsLiveData.postValue(new ArrayList<>());
            return;
        }
        final long relationshipId = currentRelationshipId;
        final long generation = relationshipGeneration.get();
        if (!isCurrentRelationship(relationshipId, generation)) {
            searchResultsLiveData.postValue(new ArrayList<>());
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.searchMessages(relationshipId, keyword);
                if (!isCurrentRelationship(relationshipId, generation)) return;
                searchResultsLiveData.postValue(convertEntitiesToMessages(entities));
            } catch (Exception e) {
                Log.e(TAG, "本地搜索失败", e);
                if (isCurrentRelationship(relationshipId, generation)) {
                    searchResultsLiveData.postValue(new ArrayList<>());
                }
            }
        });
    }

    private void syncMessageToCloud(ChatMessage message, CloudSyncCallback callback) {
        cloudChatRepository.insertMessage(message, currentUserId, new CloudChatRepository.InsertCallback() {
            @Override
            public void onSuccess(long cloudMessageId) {
                if (message.getId() > 0) {
                    databaseExecutor.execute(() -> {
                        try {
                            ChatMessageEntity entity = chatMessageDao.getMessageById(
                                    message.getId(), message.getRelationshipId());
                            if (entity != null) {
                                entity.setCloudMessageId(cloudMessageId);
                                entity.setSyncStatus(1);
                                chatMessageDao.updateMessage(entity);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "更新本地消息云端ID失败", e);
                        }
                    });
                }
                message.setCloudMessageId(cloudMessageId);
                if (callback != null) callback.onSuccess();
            }

            @Override
            public void onError(Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    private void updateLocalCache(long relationshipId, long generation,
                                  List<ChatMessage> cloudMessages, Runnable onComplete) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        if (!isCurrentRelationship(relationshipId, generation)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                if (!isCurrentRelationship(relationshipId, generation)) {
                    if (onComplete != null) onComplete.run();
                    return;
                }
                ChatDatabase database = ChatDatabase.getInstance(context);
                database.runInTransaction(() -> {
                    // 云端结果只替换已同步缓存，离线待上传消息必须保留。
                    chatMessageDao.deleteSyncedMessages(relationshipId);
                    List<ChatMessageEntity> entities = new ArrayList<>();
                    for (ChatMessage message : cloudMessages) {
                        if (message.getRelationshipId() == relationshipId) {
                            entities.add(convertMessageToEntity(message));
                        }
                    }
                    if (!entities.isEmpty()) {
                        chatMessageDao.insertMessages(entities);
                    }
                });
                if (onComplete != null && isCurrentRelationship(relationshipId, generation)) {
                    onComplete.run();
                }
            } catch (Exception e) {
                Log.e(TAG, "更新本地缓存失败", e);
                if (e.getMessage() != null &&
                        (e.getMessage().contains("SQLite") ||
                         e.getMessage().contains("database") ||
                         e.getMessage().contains("Room"))) {
                    try {
                        ChatDatabase.destroyInstance();
                        ChatDatabase.getInstance(context);
                    } catch (Exception reinitException) {
                        Log.e(TAG, "重新初始化数据库失败", reinitException);
                    }
                }
            }
        });
    }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态失败", e);
        }
        return false;
    }

    private List<ChatMessage> convertEntitiesToMessages(List<ChatMessageEntity> entities) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageEntity entity : entities) {
            ChatMessage message = new ChatMessage(
                    entity.getUsername(), entity.getUserId(), entity.getContent(), entity.getTimestamp(),
                    entity.getAvatarResId(), entity.getAvatarUri(), entity.isSentByMe());
            message.setId(entity.getId());
            message.setCloudMessageId(entity.getCloudMessageId());
            message.setLiked(entity.isLiked());
            message.setRelationshipId(entity.getRelationshipId());
            message.setMessageType(entity.getMessageType());
            messages.add(message);
        }
        return messages;
    }

    private List<ChatMessage> filterMessagesForRelationship(List<ChatMessage> messages,
                                                             long relationshipId) {
        List<ChatMessage> filtered = new ArrayList<>();
        if (messages == null) return filtered;
        for (ChatMessage message : messages) {
            if (message != null && message.getRelationshipId() == relationshipId) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private ChatMessageEntity convertMessageToEntity(ChatMessage message) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUsername(message.getUsername());
        entity.setUserId(message.getUserId());
        entity.setContent(message.getContent());
        entity.setTimestamp(message.getTimestamp());
        entity.setAvatarResId(message.getAvatarResId());
        entity.setAvatarUri(message.getAvatarUri());
        entity.setSentByMe(message.isSentByMe());
        entity.setMessageType(message.getMessageType());
        entity.setRelationshipId(message.getRelationshipId());
        if (message.getCloudMessageId() != null && message.getCloudMessageId() > 0) {
            entity.setCloudMessageId(message.getCloudMessageId());
            entity.setSyncStatus(1);
            // 云端 id 只存到 cloudMessageId，Room 本地主键交给自动递增，避免与离线消息撞 ID。
        } else if (message.getId() > 0) {
            entity.setId(message.getId());
        }
        return entity;
    }

    public void loadNewerMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        if (isNetworkAvailable()) {
            loadNewerMessagesFromCloud();
        } else {
            loadNewerMessagesFromLocal();
        }
    }

    public void loadOlderMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        if (isNetworkAvailable()) {
            loadOlderMessagesFromCloud();
        } else {
            loadOlderMessagesFromLocal();
        }
    }

    public void clearAllMessages() {
        clearAllMessages(false);
    }

    public void clearAllMessages(boolean clearDatabase) {
        allMessagesLiveData.postValue(new ArrayList<>());
        searchResultsLiveData.postValue(new ArrayList<>());

        if (clearDatabase) {
            databaseExecutor.execute(() -> {
                try {
                    // 该分支只由退出登录调用，刻意擦除本机所有账号/关系的聊天缓存。
                    chatMessageDao.deleteAllMessagesForLogout();
                } catch (Exception e) {
                    Log.e(TAG, "清空本地数据库失败", e);
                }
            });
        }
    }

    public void cleanup() {
        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
        if (cloudChatRepository != null) {
            cloudChatRepository.cleanup();
        }
    }

    public interface InsertCallback {
        void onSuccess(long id);
        void onError(Exception e);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface CloudSyncCallback {
        void onSuccess();
        void onError(Exception e);
    }
}
