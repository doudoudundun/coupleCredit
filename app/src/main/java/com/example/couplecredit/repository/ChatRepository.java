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

public class ChatRepository {

    private static final String TAG = "ChatRepository";

    private final ChatMessageDao chatMessageDao;
    private final CloudChatRepository cloudChatRepository;
    private final ExecutorService databaseExecutor;
    private final MutableLiveData<List<ChatMessage>> allMessagesLiveData;
    private final MutableLiveData<List<ChatMessage>> searchResultsLiveData;
    private final MutableLiveData<Boolean> syncStatusLiveData;
    private final MutableLiveData<String> syncErrorLiveData;
    private final Context context;

    private boolean isCloudSyncEnabled = true;
    private int currentUserId = -1;

    public ChatRepository(Context context) {
        this.context = context.getApplicationContext();
        ChatDatabase database = ChatDatabase.getInstance(context);
        chatMessageDao = database.chatMessageDao();
        cloudChatRepository = new CloudChatRepository(context);
        databaseExecutor = Executors.newFixedThreadPool(4);
        allMessagesLiveData = new MutableLiveData<>();
        searchResultsLiveData = new MutableLiveData<>();
        syncStatusLiveData = new MutableLiveData<>();
        syncErrorLiveData = new MutableLiveData<>();

        initializeUserInfoFromManager();
        testCloudConnection();
    }

    public LiveData<List<ChatMessage>> getAllMessages() {
        return allMessagesLiveData;
    }

    public LiveData<List<ChatMessage>> getSearchResults() {
        return searchResultsLiveData;
    }

    public LiveData<Boolean> getSyncStatus() {
        return syncStatusLiveData;
    }

    public LiveData<String> getSyncError() {
        return syncErrorLiveData;
    }

    public void loadAllMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;

        if (isNetworkAvailable() && isCloudSyncEnabled) {
            loadMessagesFromCloud();
        } else {
            loadMessagesFromLocal();
        }
    }

    public void insertMessage(ChatMessage message, InsertCallback callback) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (callback != null) callback.onError(new Exception("DatabaseExecutor已关闭"));
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                ChatMessageEntity entity = convertMessageToEntity(message);
                long localId = chatMessageDao.insertMessage(entity);
                Log.d(TAG, "消息已保存到本地数据库，ID: " + localId);

                loadMessagesFromLocal();

                if (isNetworkAvailable() && isCloudSyncEnabled) {
                    syncMessageToCloud(message, new CloudSyncCallback() {
                        @Override
                        public void onSuccess() {
                            syncStatusLiveData.postValue(true);
                            if (callback != null) callback.onSuccess(localId);
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.w(TAG, "消息同步到云端失败，但本地保存成功", e);
                            syncStatusLiveData.postValue(false);
                            syncErrorLiveData.postValue("云端同步失败: " + e.getMessage());
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

    public void updateMessage(ChatMessage message, UpdateCallback callback) {
        if (callback != null) callback.onSuccess();
    }

    public void deleteMessage(ChatMessage message, DeleteCallback callback) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (callback != null) callback.onError(new Exception("DatabaseExecutor已关闭"));
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                if (message.getId() > 0) {
                    chatMessageDao.deleteById(message.getId());
                } else if (message.getCloudMessageId() != null && message.getCloudMessageId() != 0) {
                    chatMessageDao.deleteByCloudId(message.getCloudMessageId());
                } else {
                    Log.e(TAG, "无法删除消息：缺少消息ID, content=" + message.getContent());
                    if (callback != null) callback.onError(new Exception("无法删除消息：缺少消息ID"));
                    return;
                }

                loadMessagesFromLocal();

                if (isNetworkAvailable() && isCloudSyncEnabled) {
                    long cloudMessageId = (message.getCloudMessageId() != null && message.getCloudMessageId() != 0)
                            ? message.getCloudMessageId() : 0;
                    cloudChatRepository.deleteMessage(cloudMessageId, message.getContent(),
                            message.getTimestamp(), new CloudChatRepository.DeleteCallback() {
                                @Override
                                public void onSuccess() {
                                    syncStatusLiveData.postValue(true);
                                }

                                @Override
                                public void onError(Exception e) {
                                    Log.w(TAG, "消息删除同步到云端失败", e);
                                    syncStatusLiveData.postValue(false);
                                    syncErrorLiveData.postValue("删除同步失败: " + e.getMessage());
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
        if (keyword == null || keyword.trim().isEmpty()) {
            searchResultsLiveData.postValue(allMessagesLiveData.getValue());
            return;
        }

        if (isNetworkAvailable() && isCloudSyncEnabled) {
            cloudChatRepository.searchMessages(keyword.trim(), new CloudChatRepository.QueryCallback() {
                @Override
                public void onSuccess(List<ChatMessage> messages) {
                    searchResultsLiveData.postValue(messages);
                }

                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "云端搜索失败，使用本地搜索", e);
                    searchMessagesFromLocal(keyword.trim());
                }
            });
        } else {
            searchMessagesFromLocal(keyword.trim());
        }
    }

    public void syncToCloud(SyncCallback callback) {
        if (!isNetworkAvailable()) {
            if (callback != null) callback.onError(new Exception("网络不可用"));
            return;
        }
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) {
            if (callback != null) callback.onError(new Exception("DatabaseExecutor已关闭"));
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                syncStatusLiveData.postValue(true);

                List<ChatMessageEntity> localEntities = chatMessageDao.getAllMessages();
                List<ChatMessage> localMessages = convertEntitiesToMessages(localEntities);

                int successCount = 0;
                int errorCount = 0;

                for (ChatMessage message : localMessages) {
                    try {
                        syncMessageToCloudSync(message);
                        successCount++;
                    } catch (Exception e) {
                        Log.w(TAG, "同步消息失败: " + message.getContent(), e);
                        errorCount++;
                    }
                }

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
                if (callback != null) callback.onError(e);
            }
        });
    }

    public void pullFromCloud(SyncCallback callback) {
        if (!isNetworkAvailable()) {
            if (callback != null) callback.onError(new Exception("网络不可用"));
            return;
        }

        cloudChatRepository.getBatchChatData(new CloudChatRepository.BatchDataCallback() {
            @Override
            public void onSuccess(CloudChatRepository.BatchChatData batchData) {
                databaseExecutor.execute(() -> {
                    try {
                        chatMessageDao.deleteAllMessages();

                        List<ChatMessageEntity> entities = new ArrayList<>();
                        for (ChatMessage message : batchData.messages) {
                            entities.add(convertMessageToEntity(message));
                        }

                        if (!entities.isEmpty()) {
                            chatMessageDao.insertMessages(entities);
                        }

                        loadMessagesFromLocal();
                        Log.d(TAG, "从云端拉取了 " + batchData.messages.size() + " 条消息");

                        if (callback != null) {
                            callback.onSuccess("拉取成功：" + batchData.messages.size() + " 条消息");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "保存云端数据到本地失败", e);
                        if (callback != null) callback.onError(e);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "从云端拉取数据失败", e);
                if (callback != null) callback.onError(e);
            }
        });
    }

    public void setCloudSyncEnabled(boolean enabled) {
        this.isCloudSyncEnabled = enabled;
    }

    private void initializeUserInfoFromManager() {
        if (UserInfoManager.isUserLoggedIn(context)) {
            int userId = UserInfoManager.getCurrentUserId(context);
            if (userId > 0) {
                this.currentUserId = userId;
            } else {
                this.currentUserId = 1;
            }
        } else {
            this.currentUserId = 1;
        }

        if (cloudChatRepository != null) {
            cloudChatRepository.initializeUserInfo();
        }
    }

    public void setCurrentUserId(int userId) {
        this.currentUserId = userId;
        cloudChatRepository.initializeUserInfo();
    }

    public void saveDefaultMessages() {}

    private void loadMessagesFromLocal() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;

        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                allMessagesLiveData.postValue(messages);
            } catch (Exception e) {
                Log.e(TAG, "从本地加载消息失败", e);
                allMessagesLiveData.postValue(new ArrayList<>());
            }
        });
    }

    private void loadMessagesFromCloud() {
        cloudChatRepository.getBatchChatData(new CloudChatRepository.BatchDataCallback() {
            @Override
            public void onSuccess(CloudChatRepository.BatchChatData batchData) {
                allMessagesLiveData.postValue(batchData.messages);
                syncStatusLiveData.postValue(true);
                updateLocalCache(batchData.messages);
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "从云端加载消息失败，使用本地数据", e);
                syncStatusLiveData.postValue(false);
                syncErrorLiveData.postValue("云端加载失败: " + e.getMessage());
                loadMessagesFromLocal();
            }
        });
    }

    private void loadNewerMessagesFromCloud() {
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> newMessages) {
                if (newMessages == null || newMessages.isEmpty()) return;

                List<ChatMessage> allMessages = allMessagesLiveData.getValue();
                if (allMessages == null) allMessages = new ArrayList<>();

                List<ChatMessage> updatedMessages = new ArrayList<>(allMessages);
                for (ChatMessage newMsg : newMessages) {
                    if (!updatedMessages.contains(newMsg)) {
                        updatedMessages.add(newMsg);
                    }
                }
                allMessagesLiveData.postValue(updatedMessages);
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "从云端加载新消息失败", e);
                loadNewerMessagesFromLocal();
            }
        });
    }

    private void loadOlderMessagesFromCloud() {
        cloudChatRepository.getAllMessages(new CloudChatRepository.QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> olderMessages) {
                if (olderMessages == null || olderMessages.isEmpty()) return;

                List<ChatMessage> allMessages = allMessagesLiveData.getValue();
                if (allMessages == null) allMessages = new ArrayList<>();

                List<ChatMessage> updatedMessages = new ArrayList<>();
                for (ChatMessage olderMsg : olderMessages) {
                    if (!allMessages.contains(olderMsg)) {
                        updatedMessages.add(olderMsg);
                    }
                }
                updatedMessages.addAll(allMessages);
                allMessagesLiveData.postValue(updatedMessages);
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "从云端加载历史消息失败", e);
                loadOlderMessagesFromLocal();
            }
        });
    }

    private void loadNewerMessagesFromLocal() {
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
                List<ChatMessage> messages = convertEntitiesToMessages(entities);
                List<ChatMessage> currentMessages = allMessagesLiveData.getValue();
                if (currentMessages == null || messages.size() > currentMessages.size()) {
                    allMessagesLiveData.postValue(messages);
                }
            } catch (Exception e) {
                Log.e(TAG, "从本地加载新消息失败", e);
            }
        });
    }

    private void loadOlderMessagesFromLocal() {
        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();
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

        databaseExecutor.execute(() -> {
            try {
                List<ChatMessageEntity> entities = chatMessageDao.searchMessages(keyword);
                searchResultsLiveData.postValue(convertEntitiesToMessages(entities));
            } catch (Exception e) {
                Log.e(TAG, "本地搜索失败", e);
                searchResultsLiveData.postValue(new ArrayList<>());
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
                            ChatMessageEntity entity = chatMessageDao.getMessageById(message.getId());
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

    private void syncMessageToCloudSync(ChatMessage message) throws Exception {
        final Exception[] syncException = {null};
        final boolean[] syncCompleted = {false};

        syncMessageToCloud(message, new CloudSyncCallback() {
            @Override
            public void onSuccess() { syncCompleted[0] = true; }

            @Override
            public void onError(Exception e) {
                syncException[0] = e;
                syncCompleted[0] = true;
            }
        });

        while (!syncCompleted[0]) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("同步被中断", e);
            }
        }

        if (syncException[0] != null) throw syncException[0];
    }

    private void updateLocalCache(List<ChatMessage> cloudMessages) {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;

        databaseExecutor.execute(() -> {
            try {
                ChatDatabase database = ChatDatabase.getInstance(context);
                database.runInTransaction(() -> {
                    chatMessageDao.deleteAllMessages();
                    List<ChatMessageEntity> entities = new ArrayList<>();
                    for (ChatMessage message : cloudMessages) {
                        entities.add(convertMessageToEntity(message));
                    }
                    if (!entities.isEmpty()) {
                        chatMessageDao.insertMessages(entities);
                    }
                });
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

    private void testCloudConnection() {
        if (isNetworkAvailable()) {
            cloudChatRepository.testConnection(new CloudChatRepository.ConnectionTestCallback() {
                @Override
                public void onSuccess(boolean connected, String message) {
                    syncStatusLiveData.postValue(true);
                }
            });
        } else {
            syncStatusLiveData.postValue(false);
        }
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
        if (message.getId() > 0) entity.setId(message.getId());
        if (message.getCloudMessageId() != null && message.getCloudMessageId() > 0) {
            entity.setCloudMessageId(message.getCloudMessageId());
        }
        return entity;
    }

    public void loadNewerMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        if (isNetworkAvailable() && isCloudSyncEnabled) {
            loadNewerMessagesFromCloud();
        } else {
            loadNewerMessagesFromLocal();
        }
    }

    public void loadOlderMessages() {
        if (databaseExecutor.isShutdown() || databaseExecutor.isTerminated()) return;
        if (isNetworkAvailable() && isCloudSyncEnabled) {
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
        syncStatusLiveData.postValue(false);
        syncErrorLiveData.postValue(null);

        if (clearDatabase) {
            databaseExecutor.execute(() -> {
                try {
                    chatMessageDao.deleteAllMessages();
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

    public ChatMessageDao getChatMessageDao() {
        return chatMessageDao;
    }

    public CloudChatRepository getCloudRepository() {
        return cloudChatRepository;
    }

    public interface InsertCallback {
        void onSuccess(long id);
        void onError(Exception e);
    }

    public interface UpdateCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(Exception e);
    }

    public interface CloudSyncCallback {
        void onSuccess();
        void onError(Exception e);
    }
}
