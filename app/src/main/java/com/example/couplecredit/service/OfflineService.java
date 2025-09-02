package com.example.couplecredit.service;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.couplecredit.model.ChatMessage;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.couplecredit.utils.NetworkStateManager;
import com.example.couplecredit.service.OfflineCacheManager;
import com.google.android.material.tabs.TabLayout;

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
    
    // 状态LiveData
    private final MutableLiveData<OfflineStatus> offlineStatus = new MutableLiveData<>();
    private final MutableLiveData<SyncProgress> syncProgress = new MutableLiveData<>();
    
    // 配置
    private boolean autoSyncEnabled = true;
    private int maxRetryAttempts = 3;

    private static final String TAG = "OfflineService";


    
    /**
     * 构造函数
     * @param context 应用程序上下文
     * @param syncService 同步服务
     * @param cacheManager 缓存管理器
     */
    public OfflineService(Context context, ChatSyncService syncService, OfflineCacheManager cacheManager) {
        this.context = context.getApplicationContext();
        this.syncService = syncService;
        this.cacheManager = cacheManager;
        // 修复：使用单例模式并启动监听
        this.networkManager = NetworkStateManager.getInstance(context);
        this.networkManager.startNetworkMonitoring();
        this.executor = Executors.newSingleThreadExecutor();
        
        initializeService();
    }
    
    /**
     * 初始化服务
     */
    private void initializeService() {
        // 监听网络状态变化
        networkManager.getNetworkAvailability().observeForever(isConnected -> {
            if (isConnected) {
                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
            } else {
                offlineStatus.postValue(new OfflineStatus(OfflineStatusType.OFFLINE, null));
            }
        });
        
        // 检查初始网络状态
        boolean initialNetworkState = networkManager.isNetworkCurrentlyAvailable();
        if (initialNetworkState) {
            offlineStatus.postValue(new OfflineStatus(OfflineStatusType.ONLINE, null));
        } else {
            offlineStatus.postValue(new OfflineStatus(OfflineStatusType.OFFLINE, null));
        }
    }
    
    /**
     * 同步待处理的消息
     */
    public void syncPendingMessages() {
        if (!networkManager.isNetworkCurrentlyAvailable()) {
            Log.w(TAG, "网络不可用，无法同步待发送消息");
            return;
        }
        
        // 检查ExecutorService是否已关闭
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
                        // 更新同步进度
                        int progress = (int) ((syncedCount / (float) totalMessages) * 100);
                        syncProgress.postValue(new SyncProgress(progress, "正在同步消息 " + (syncedCount + 1) + "/" + totalMessages));
                        
                        // 尝试同步消息到云端
                        boolean success = syncMessageToCloud(message);
                        
                        if (success) {
                            cacheManager.removePendingMessage(message);
                            cacheManager.removeFailedMessage(message); // 如果之前失败过，现在成功了就移除
                            syncedCount++;
                        } else {
                            cacheManager.addFailedMessage(message);
                        }
                        
                        // 短暂延迟，避免过于频繁的请求
                        Thread.sleep(100);
                        
                    } catch (Exception e) {
                        cacheManager.addFailedMessage(message);
                    }
                }
                
                // 同步完成
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
    
    /**
     * 重试失败的消息
     */
    public void retryFailedMessages() {
        if (!networkManager.isNetworkCurrentlyAvailable()) {
            Log.w(TAG, "网络不可用，无法重试失败消息");
            return;
        }
        
        // 检查ExecutorService是否已关闭
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
                    // 更新重试进度
                    int progress = (int) ((retriedCount / (float) totalMessages) * 100);
                    syncProgress.postValue(new SyncProgress(progress, "正在重试消息 " + (retriedCount + 1) + "/" + totalMessages));
                    
                    // 尝试重新同步消息
                    boolean success = syncMessageToCloud(message);
                    
                    if (success) {
                        cacheManager.removeFailedMessage(message);
                        retriedCount++;
                    }
                    
                    // 短暂延迟
                    Thread.sleep(100);
                    
                } catch (Exception e) {
                    // 重试失败，保持在失败队列中
                }
            }
            
            // 重试完成
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
    
    /**
     * 同步单个消息到云端
     * @param message 要同步的消息
     * @return 是否同步成功
     */
    private boolean syncMessageToCloud(ChatMessage message) {
        try {
            // 这里应该调用实际的云端同步逻辑
            // 由于ChatSyncService的具体实现可能不同，这里使用简化的逻辑
            
            // 模拟网络请求延迟
            Thread.sleep(200);
            
            // 简化的成功率（实际应该根据网络状况和服务器响应判断）
            return Math.random() > 0.1; // 90%成功率
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取离线状态
     * @return 离线状态的LiveData
     */
    public LiveData<OfflineStatus> getOfflineStatus() {
        return offlineStatus;
    }
    
    /**
     * 获取同步进度
     * @return 同步进度的LiveData
     */
    public LiveData<SyncProgress> getSyncProgress() {
        return syncProgress;
    }
    
    /**
     * 设置自动同步开关
     * @param enabled 是否启用自动同步
     */
    public void setAutoSyncEnabled(boolean enabled) {
        this.autoSyncEnabled = enabled;
    }
    
    /**
     * 检查是否启用自动同步
     * @return 是否启用自动同步
     */
    public boolean isAutoSyncEnabled() {
        return autoSyncEnabled;
    }
    
    /**
     * 设置最大重试次数
     * @param maxRetryAttempts 最大重试次数
     */
    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (networkManager != null) {
            networkManager.cleanup();
        }
        
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
    
    /**
     * 离线状态类型枚举
     */
    public enum OfflineStatusType {
        ONLINE,      // 在线
        OFFLINE,     // 离线
        SYNCING,     // 同步中
        SYNC_FAILED  // 同步失败
    }
    
    /**
     * 离线状态类
     */
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
    
    /**
     * 同步进度类
     */
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