package com.example.couplecredit.service;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.utils.NetworkStateManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 离线服务类
 * 整合网络状态监听和离线缓存功能，提供统一的离线处理接口
 */
public class OfflineService {

    private final Context context;
    private final ChatSyncService syncService;
    private final OfflineCacheManager cacheManager;
    private final NetworkStateManager networkManager;
    private final ExecutorService executor;

    private final MutableLiveData<OfflineStatus> offlineStatus = new MutableLiveData<>();
    private final MutableLiveData<SyncProgress> syncProgress = new MutableLiveData<>();

    private boolean autoSyncEnabled = true;
    private int maxRetryAttempts = 3;

    private static final String TAG = "OfflineService";

    public OfflineService(Context context, ChatSyncService syncService, OfflineCacheManager cacheManager) {
        this.context = context.getApplicationContext();
        this.syncService = syncService;
        this.cacheManager = cacheManager;
        this.networkManager = NetworkStateManager.getInstance(context);
        this.networkManager.startNetworkMonitoring();
        this.executor = Executors.newSingleThreadExecutor();
        initializeService();
    }

    private void initializeService() {
        networkManager.getNetworkAvailability().observeForever(isConnected -> {
            if (isConnected) {
                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
            } else {
                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.OFFLINE, null));
            }
        });

        boolean initialNetworkState = networkManager.isNetworkCurrentlyAvailable();
        if (initialNetworkState) {
            offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
        } else {
            offlineStatus.postValue(new OfflineStatus(OfflineStatusType.OFFLINE, null));
        }
    }

    public void syncPendingMessages() {
        if (!networkManager.isNetworkCurrentlyAvailable()) {
            Log.w(TAG, "网络不可用，无法同步待发送消息");
            return;
        }
        if (executor.isShutdown() || executor.isTerminated()) {
            return;
        }

        try {
            executor.execute(() -> {
                List<ChatMessage> pendingMessages = cacheManager.getPendingMessages();
                if (pendingMessages.isEmpty()) {
                    return;
                }

                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.SYNCING, null));

                int totalMessages = pendingMessages.size();
                int syncedCount = 0;

                for (ChatMessage message : pendingMessages) {
                    try {
                        int progress = (int) ((syncedCount / (float) totalMessages) * 100);
                        syncProgress.postValue(new SyncProgress(progress, "正在同步消息 " + (syncedCount + 1) + "/" + totalMessages));

                        boolean success = syncMessageToCloud(message);
                        if (success) {
                            cacheManager.removePendingMessage(message);
                            cacheManager.removeFailedMessage(message);
                            syncedCount++;
                        } else {
                            cacheManager.addFailedMessage(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "同步单条消息失败", e);
                        cacheManager.addFailedMessage(message);
                    }
                }

                syncProgress.postValue(new SyncProgress(100, "同步完成"));
                if (syncedCount == totalMessages) {
                    offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
                } else {
                    offlineStatus.postValue(new OfflineStatus(OfflineStatusType.SYNC_FAILED,
                            "部分消息同步失败，已加入重试队列"));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "ExecutorService已关闭，无法执行同步任务");
        } catch (Exception e) {
            Log.e(TAG, "同步消息时发生异常", e);
        }
    }

    public void retryFailedMessages() {
        if (!networkManager.isNetworkCurrentlyAvailable()) {
            Log.w(TAG, "网络不可用，无法重试失败消息");
            return;
        }
        if (executor.isShutdown() || executor.isTerminated()) {
            return;
        }

        try {
            executor.execute(() -> {
                List<ChatMessage> failedMessages = cacheManager.getFailedMessages();
                if (failedMessages.isEmpty()) {
                    return;
                }

                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.SYNCING, null));

                int totalMessages = failedMessages.size();
                int retriedCount = 0;

                for (ChatMessage message : failedMessages) {
                    try {
                        int progress = (int) ((retriedCount / (float) totalMessages) * 100);
                        syncProgress.postValue(new SyncProgress(progress, "正在重试消息 " + (retriedCount + 1) + "/" + totalMessages));

                        boolean success = syncMessageToCloud(message);
                        if (success) {
                            cacheManager.removeFailedMessage(message);
                            cacheManager.removePendingMessage(message);
                            retriedCount++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "重试单条消息失败", e);
                    }
                }

                syncProgress.postValue(new SyncProgress(100, "重试完成"));
                if (retriedCount == totalMessages) {
                    offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
                } else {
                    offlineStatus.postValue(new OfflineStatus(OfflineStatusType.SYNC_FAILED,
                            "部分消息重试失败"));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "ExecutorService已关闭，无法执行重试任务");
        } catch (Exception e) {
            Log.e(TAG, "重试消息时发生异常", e);
        }
    }

    private boolean syncMessageToCloud(ChatMessage message) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};

            syncService.uploadOfflineMessage(message, new ChatSyncService.SingleMessageSyncCallback() {
                @Override
                public void onSuccess() {
                    success[0] = true;
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "离线消息同步失败: " + error);
                    latch.countDown();
                }
            });

            boolean completed = latch.await(15, TimeUnit.SECONDS);
            return completed && success[0];
        } catch (Exception e) {
            Log.e(TAG, "同步单个消息异常", e);
            return false;
        }
    }

    public LiveData<OfflineStatus> getOfflineStatus() {
        return offlineStatus;
    }

    public LiveData<SyncProgress> getSyncProgress() {
        return syncProgress;
    }

    public void setAutoSyncEnabled(boolean enabled) {
        this.autoSyncEnabled = enabled;
    }

    public boolean isAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public void cleanup() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    public static class OfflineStatus {
        private final OfflineStatusType status;
        private final String errorMessage;

        public OfflineStatus(OfflineStatusType status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public OfflineStatusType getStatus() {
            return status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public enum OfflineStatusType {
        ONLINE,
        OFFLINE,
        SYNCING,
        SYNC_FAILED
    }

    public static class SyncProgress {
        private final int progressPercentage;
        private final String currentOperation;

        public SyncProgress(int progressPercentage, String currentOperation) {
            this.progressPercentage = progressPercentage;
            this.currentOperation = currentOperation;
        }

        public int getProgressPercentage() {
            return progressPercentage;
        }

        public String getCurrentOperation() {
            return currentOperation;
        }
    }
}
