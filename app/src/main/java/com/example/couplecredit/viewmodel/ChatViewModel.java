package com.example.couplecredit.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.repository.ChatRepository;
import com.example.couplecredit.service.ChatSyncService;
import com.example.couplecredit.utils.NetworkStateManager;
import com.example.couplecredit.service.OfflineCacheManager;
import com.example.couplecredit.service.OfflineService;
import com.example.couplecredit.function.UserInfoManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天界面ViewModel
 * 负责管理聊天界面的业务逻辑和UI状态
 * 实现MVVM架构中的ViewModel层，连接View和Repository
 * 支持云端数据库同步功能、网络状态检测和离线缓存
 */
public class ChatViewModel extends AndroidViewModel {
    
    // ==================== 成员变量 ====================
    
    private final ChatRepository chatRepository;
    private ChatSyncService syncService;
    private NetworkStateManager networkStateManager;
    private OfflineCacheManager offlineCacheManager;
    private OfflineService offlineService;
    
    // UI状态相关的LiveData
    private final MutableLiveData<Boolean> isSearchMode = new MutableLiveData<>(false);
    private final MutableLiveData<String> searchKeyword = new MutableLiveData<>("");
    private final MutableLiveData<String> messageInput = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String> successMessage = new MutableLiveData<>();
    
    // 云端同步相关的LiveData
    private final MutableLiveData<Boolean> isCloudSyncEnabled = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> isNetworkAvailable = new MutableLiveData<>(true);
    private final MutableLiveData<String> syncStatusMessage = new MutableLiveData<>();
    private final MutableLiveData<Integer> syncProgress = new MutableLiveData<>(0);
    
    // 离线缓存相关的LiveData
    private final MutableLiveData<Integer> pendingMessagesCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> failedMessagesCount = new MutableLiveData<>(0);
    private final MutableLiveData<String> offlineStatusMessage = new MutableLiveData<>();
    
    // 用户信息
    private int currentUserId = -1; // 从UserInfoManager获取
    private long currentRelationshipId = -1; // 从UserInfoManager获取
    
    // 时间格式化器
    private final SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm", Locale.getDefault());
    
    // ==================== 构造函数 ====================
    
    /**
     * 主构造函数
     * @param application 应用程序实例
     * @param repository 聊天数据仓库
     */
    public ChatViewModel(@NonNull Application application, @NonNull ChatRepository repository) {
        super(application);
        this.chatRepository = repository;
        initializeCommonComponents(application);
    }
    
    /**
     * 原有构造函数（保持兼容性）
     * @param application 应用程序实例
     */
    public ChatViewModel(@NonNull Application application) {
        super(application);
        chatRepository = new ChatRepository(application);
        initializeCommonComponents(application);
    }
    
    /**
     * 初始化公共组件和服务
     * @param application 应用程序实例
     */
    private void initializeCommonComponents(@NonNull Application application) {
        this.syncService = new ChatSyncService(chatRepository.getChatMessageDao(), chatRepository.getCloudRepository());
        
        // 使用单例模式
        this.networkStateManager = NetworkStateManager.getInstance(application);
        this.networkStateManager.startNetworkMonitoring();
        this.offlineCacheManager = OfflineCacheManager.getInstance(application);
        this.offlineService = new OfflineService(application, syncService, offlineCacheManager);
        
        // 从UserInfoManager获取用户信息
        initializeUserInfoFromManager(application);
        
        // 监听同步状态
        observeSyncStatus();
        
        // 监听网络状态和离线缓存状态
        observeNetworkAndOfflineStatus();
        
        // 注意：不在构造函数中自动初始化数据，由Fragment根据登录状态决定是否调用
    }
    
    // ==================== 数据访问方法 ====================
    
    /**
     * 获取所有消息的LiveData
     * @return 消息列表的LiveData
     */
    public LiveData<List<ChatMessage>> getAllMessages() {
        return chatRepository.getAllMessages();
    }
    
    /**
     * 获取搜索结果的LiveData
     * @return 搜索结果的LiveData
     */
    public LiveData<List<ChatMessage>> getSearchResults() {
        return chatRepository.getSearchResults();
    }
    
    /**
     * 获取搜索模式状态
     * @return 搜索模式状态的LiveData
     */
    public LiveData<Boolean> getIsSearchMode() {
        return isSearchMode;
    }
    
    /**
     * 获取搜索关键词
     * @return 搜索关键词的LiveData
     */
    public LiveData<String> getSearchKeyword() {
        return searchKeyword;
    }
    
    /**
     * 获取消息输入内容
     * @return 消息输入内容的LiveData
     */
    public LiveData<String> getMessageInput() {
        return messageInput;
    }
    
    /**
     * 获取加载状态
     * @return 加载状态的LiveData
     */
    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }
    
    /**
     * 获取错误消息
     * @return 错误消息的LiveData
     */
    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * 获取成功消息
     * @return 成功消息的LiveData
     */
    public LiveData<String> getSuccessMessage() {
        return successMessage;
    }
    
    // ==================== 云端同步相关方法 ====================
    
    /**
     * 获取云端同步启用状态
     * @return 云端同步启用状态的LiveData
     */
    public LiveData<Boolean> getIsCloudSyncEnabled() {
        return isCloudSyncEnabled;
    }
    
    /**
     * 获取网络可用状态
     * @return 网络可用状态的LiveData
     */
    public LiveData<Boolean> getIsNetworkAvailable() {
        return isNetworkAvailable;
    }
    
    /**
     * 获取同步状态消息
     * @return 同步状态消息的LiveData
     */
    public LiveData<String> getSyncStatusMessage() {
        return syncStatusMessage;
    }
    
    /**
     * 获取同步进度
     * @return 同步进度的LiveData
     */
    public LiveData<Integer> getSyncProgress() {
        return syncProgress;
    }
    
    /**
     * 获取同步服务的状态LiveData
     * @return 同步状态的LiveData
     */
    public LiveData<ChatSyncService.SyncStatus> getSyncStatus() {
        return syncService.getSyncStatusLiveData();
    }
    
    /**
     * 获取同步服务的进度LiveData
     * @return 同步进度的LiveData
     */
    public LiveData<ChatSyncService.SyncProgress> getSyncProgressDetail() {
        return syncService.getSyncProgressLiveData();
    }
    
    /**
     * 获取同步错误LiveData
     * @return 同步错误的LiveData
     */
    public LiveData<String> getSyncError() {
        return syncService.getSyncErrorLiveData();
    }
    
    // ==================== 业务逻辑方法 ====================
    
    /**
     * 初始化数据
     * 用于Fragment中的数据初始化调用
     */
    public void initializeData() {
        // 检查用户登录状态，未登录则不加载聊天信息
        if (!UserInfoManager.isUserLoggedIn(getApplication())) {
            return;
        }
        
        // 加载消息数据
        loadMessages();
        
        // 重置UI状态
        isSearchMode.postValue(false);
        searchKeyword.postValue("");
        messageInput.postValue("");
        isLoading.postValue(false);
        
        // 清除之前的错误和成功消息
        errorMessage.postValue(null);
        successMessage.postValue(null);
        
        // 检查网络状态
        checkNetworkStatus();
        
        // 如果启用云端同步，执行增量同步
        if (Boolean.TRUE.equals(isCloudSyncEnabled.getValue()) && 
            Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            performIncrementalSync();
        }
    }
    
    /**
     * 加载消息列表
     */
    public void loadMessages() {
        isLoading.postValue(true);
        chatRepository.loadAllMessages();
        // 已移除默认消息加载：chatRepository.saveDefaultMessages();
        isLoading.postValue(false);
    }
    
    /**
     * 发送消息
     * @param content 消息内容
     */
    public void sendMessage(String content) {
        if (!isValidMessageContent(content)) {
            errorMessage.postValue("消息内容不能为空");
            return;
        }
        
        isLoading.postValue(true);
        
        try {
            // 创建消息对象
            ChatMessage message = new ChatMessage(
                "用户", // 实际应从用户会话获取
                content.trim(),
                getCurrentTimestamp(),
                android.R.drawable.ic_menu_gallery, // 默认头像
                true // isSentByMe
            );
            
            // 设置云端相关信息
            message.setUserId(currentUserId);
            message.setRelationshipId(currentRelationshipId);
            message.setMessageType("text");
            
            // 检查网络状态
            Boolean networkAvailable = isNetworkAvailable.getValue();
            if (networkAvailable != null && networkAvailable && isCloudSyncEnabled.getValue() != null && isCloudSyncEnabled.getValue()) {
                // 网络可用且启用云端同步，直接发送到云端
                chatRepository.insertMessage(message, new ChatRepository.InsertCallback() {
                    @Override
                    public void onSuccess(long id) {
                        successMessage.postValue("消息发送成功");
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        errorMessage.postValue("发送消息失败: " + e.getMessage());
                    }
                });
            } else {
                // 网络不可用或未启用云端同步，缓存到本地
                chatRepository.insertMessage(message, new ChatRepository.InsertCallback() {
                    @Override
                    public void onSuccess(long id) {
                        offlineCacheManager.addPendingMessage(message);
                        successMessage.postValue("消息已缓存，网络恢复后将自动同步");
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        errorMessage.postValue("发送消息失败: " + e.getMessage());
                    }
                });
            }
            
            // 清空输入框
                messageInput.postValue("");
            } catch (Exception e) {
                errorMessage.postValue("发送消息失败: " + e.getMessage());
            } finally {
                isLoading.postValue(false);
        }
    }
    
    /**
     * 搜索消息
     * @param keyword 搜索关键词
     */
    public void searchMessages(String keyword) {
        searchKeyword.postValue(keyword);
        
        if (keyword == null || keyword.trim().isEmpty()) {
            // 退出搜索模式
            exitSearchMode();
            return;
        }
        
        // 进入搜索模式
        isSearchMode.postValue(true);
        chatRepository.searchMessages(keyword.trim());
    }
    
    /**
     * 退出搜索模式
     */
    public void exitSearchMode() {
        isSearchMode.postValue(false);
        searchKeyword.postValue("");
        // 重新加载所有消息
        loadMessages();
    }
    
    /**
     * 切换消息点赞状态
     * @param message 要切换点赞状态的消息
     */
    public void toggleMessageLike(ChatMessage message) {
        if (message == null) {
            errorMessage.postValue("消息不存在");
            return;
        }
        
        // 注意：message的状态已经在UI层更新，这里只负责数据库同步
        // 不再在这里修改message状态，避免与UI层的更新冲突
        
        // 更新数据库（会自动同步到云端）
        chatRepository.updateMessage(message, new ChatRepository.UpdateCallback() {
            @Override
            public void onSuccess() {
                String status = message.isLiked() ? "点赞" : "取消点赞";
                successMessage.postValue(status + "成功");
            }
            
            @Override
            public void onError(Exception e) {
                // 数据库更新失败时，通过错误消息通知用户
                // UI层需要根据错误回滚状态
                errorMessage.postValue("操作失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 删除消息
     * @param message 要删除的消息
     */
    public void deleteMessage(ChatMessage message) {
        if (message == null) {
            errorMessage.postValue("消息不存在");
            return;
        }
        
        isLoading.postValue(true);
        
        chatRepository.deleteMessage(message, new ChatRepository.DeleteCallback() {
            @Override
            public void onSuccess() {
                isLoading.postValue(false);
                successMessage.postValue("消息删除成功");
            }
            
            @Override
            public void onError(Exception e) {
                isLoading.postValue(false);
                errorMessage.postValue("删除失败: " + e.getMessage());
            }
        });
    }
    
    // ==================== 云端同步操作方法 ====================
    
    /**
     * 执行完整数据迁移
     */
    public void performFullMigration() {
        if (!Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            errorMessage.postValue("网络不可用，无法执行数据迁移");
            return;
        }
        
        syncStatusMessage.postValue("正在执行数据迁移...");
        
        syncService.performFullMigration(new ChatSyncService.MigrationCallback() {
            @Override
            public void onSuccess(int migratedCount) {
                syncStatusMessage.postValue(String.format("数据迁移完成，成功迁移 %d 条消息", migratedCount));
                successMessage.postValue("数据迁移成功");
            }
            
            @Override
            public void onPartialSuccess(int successCount, int failureCount) {
                syncStatusMessage.postValue(String.format("数据迁移部分成功：成功 %d 条，失败 %d 条", successCount, failureCount));
                errorMessage.postValue("数据迁移部分失败");
            }
            
            @Override
            public void onError(String error) {
                syncStatusMessage.postValue("数据迁移失败");
                errorMessage.postValue("迁移失败: " + error);
            }
        });
    }
    
    /**
     * 执行增量同步
     */
    public void performIncrementalSync() {
        if (!Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            return; // 静默失败，不显示错误
        }
        
        syncService.performIncrementalSync(new ChatSyncService.SyncCallback() {
            @Override
            public void onSuccess(ChatSyncService.SyncResult result) {
                String message = String.format("同步完成：上传 %d 条，下载 %d 条，解决冲突 %d 条", 
                    result.uploadedCount, result.downloadedCount, result.conflictCount);
                syncStatusMessage.postValue(message);
                
                // 重新加载消息以显示最新数据
                loadMessages();
            }
            
            @Override
            public void onError(String error) {
                syncStatusMessage.postValue("同步失败: " + error);
            }
        });
    }
    
    /**
     * 手动同步到云端
     */
    public void syncToCloud() {
        if (!Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            errorMessage.postValue("网络不可用，无法同步到云端");
            return;
        }
        
        syncStatusMessage.postValue("正在同步到云端...");
        chatRepository.syncToCloud(new ChatRepository.SyncCallback() {
            @Override
            public void onSuccess(String message) {
                syncStatusMessage.postValue("同步到云端成功");
                successMessage.postValue("数据已同步到云端");
            }
            
            @Override
            public void onError(Exception e) {
                syncStatusMessage.postValue("同步到云端失败");
                errorMessage.postValue("同步失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 从云端拉取数据
     */
    public void pullFromCloud() {
        if (!Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            errorMessage.postValue("网络不可用，无法从云端拉取数据");
            return;
        }
        
        syncStatusMessage.postValue("正在从云端拉取数据...");
        chatRepository.pullFromCloud(new ChatRepository.SyncCallback() {
            @Override
            public void onSuccess(String message) {
                syncStatusMessage.postValue("从云端拉取数据成功");
                successMessage.postValue("已获取最新数据");
                loadMessages(); // 重新加载消息
            }
            
            @Override
            public void onError(Exception e) {
                syncStatusMessage.postValue("从云端拉取数据失败");
                errorMessage.postValue("拉取失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 切换云端同步开关
     * @param enabled 是否启用云端同步
     */
    public void setCloudSyncEnabled(boolean enabled) {
        isCloudSyncEnabled.postValue(enabled);
        chatRepository.setCloudSyncEnabled(enabled);
        
        if (enabled && Boolean.TRUE.equals(isNetworkAvailable.getValue())) {
            // 启用同步时执行一次增量同步
            performIncrementalSync();
        }
        
        String message = enabled ? "云端同步已启用" : "云端同步已禁用";
        successMessage.postValue(message);
    }
    
    /**
     * 从UserInfoManager初始化用户信息
     */
    private void initializeUserInfoFromManager(Application application) {
        if (UserInfoManager.isUserLoggedIn(application)) {
            int userId = UserInfoManager.getCurrentUserId(application);
            if (userId > 0) {
                this.currentUserId = userId;
                // 异步获取完整用户信息包括relationshipId
                UserInfoManager.getCurrentUserInfo(application, new UserInfoManager.UserInfoCallback() {
                    @Override
                    public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                        currentRelationshipId = relationshipId != null ? relationshipId : 1;
                        android.util.Log.d("ChatViewModel", "从UserInfoManager获取用户信息: userId=" + userId + ", relationshipId=" + currentRelationshipId);
                        
                        // 更新syncService和chatRepository的用户信息
                        if (syncService != null) {
                            syncService.setCurrentUserInfo(currentUserId, currentRelationshipId);
                        }
                        if (chatRepository != null) {
                            chatRepository.setCurrentUserId(currentUserId);
                        }
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.util.Log.w("ChatViewModel", "获取用户信息失败: " + error + "，使用默认relationshipId");
                        currentRelationshipId = 1;
                    }
                });
            } else {
                android.util.Log.w("ChatViewModel", "UserInfoManager中没有有效的用户ID，使用默认值");
                this.currentUserId = 1;
                this.currentRelationshipId = 1;
            }
        } else {
            android.util.Log.w("ChatViewModel", "用户未登录，使用默认值");
            this.currentUserId = 1;
            this.currentRelationshipId = 1;
        }
        
        // 立即设置基本用户信息到相关组件
        syncService.setCurrentUserInfo(currentUserId, currentRelationshipId);
        chatRepository.setCurrentUserId(currentUserId);
    }
    
    /**
     * 设置用户信息
     * @param userId 用户ID
     * @param relationshipId 关系ID
     */
    public void setUserInfo(int userId, long relationshipId) {
        this.currentUserId = userId;
        this.currentRelationshipId = relationshipId;
        
        // 更新Repository和SyncService
        chatRepository.setCurrentUserId(userId);
        syncService.setCurrentUserInfo(userId, relationshipId);
    }
    
    /**
     * 从UserInfoManager自动获取并设置用户信息
     */
    public void refreshUserInfoFromManager() {
        Application application = getApplication();
        
        if (UserInfoManager.isUserLoggedIn(application)) {
            int userId = UserInfoManager.getCurrentUserId(application);
            if (userId > 0) {
                // 异步获取完整用户信息
                UserInfoManager.getCurrentUserInfo(application, new UserInfoManager.UserInfoCallback() {
                    @Override
                    public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                        long relId = relationshipId != null ? relationshipId : 1;
                        
                        // 更新用户信息
                        setUserInfo(userId, relId);
                        
                        android.util.Log.d("ChatViewModel", "用户信息已从UserInfoManager刷新: userId=" + userId + ", relationshipId=" + relId);
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.util.Log.w("ChatViewModel", "刷新用户信息失败: " + error);
                    }
                });
            } else {
                android.util.Log.w("ChatViewModel", "UserInfoManager中没有有效的用户ID");
            }
        } else {
            android.util.Log.w("ChatViewModel", "用户未登录，无法刷新用户信息");
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 监听同步状态
     */
    private void observeSyncStatus() {
        // 监听同步进度
        syncService.getSyncProgressLiveData().observeForever(progress -> {
            if (progress != null) {
                syncProgress.postValue(progress.getPercentage());
        syncStatusMessage.postValue(progress.message);
            }
        });
        
        // 监听同步错误
        syncService.getSyncErrorLiveData().observeForever(error -> {
            if (error != null && !error.isEmpty()) {
                errorMessage.postValue(error);
            }
        });
    }
    
    /**
     * 检查网络状态
     */
    private void checkNetworkStatus() {
        // 这里应该实现实际的网络检查逻辑
        // 可以使用ConnectivityManager或其他网络检测方法
        boolean networkAvailable = chatRepository.isNetworkAvailable();
        isNetworkAvailable.setValue(networkAvailable);
        
        if (!networkAvailable) {
            syncStatusMessage.setValue("网络不可用，已切换到离线模式");
        }
    }
    
    // ==================== 原有方法保持不变 ====================
    
    /**
     * 复制消息内容
     * @param message 要复制的消息
     * @return 复制的内容
     */
    public String copyMessageContent(ChatMessage message) {
        if (message == null || message.getContent() == null) {
            errorMessage.postValue("消息内容为空");
            return "";
        }
        
        successMessage.postValue("消息已复制到剪贴板");
        return message.getContent();
    }
    
    /**
     * 设置消息输入内容
     * @param input 输入内容
     */
    public void setMessageInput(String input) {
        messageInput.postValue(input);
    }
    
    /**
     * 清除错误消息
     */
    public void clearErrorMessage() {
        errorMessage.postValue(null);
    }
    
    /**
     * 清空聊天消息显示（用于退出登录时）
     */
    public void clearChatMessages() {
        clearChatMessages(true); // 默认完全清理，包括数据库
    }
    
    /**
     * 清空聊天消息
     * @param clearDatabase 是否同时清空本地数据库
     */
    public void clearChatMessages(boolean clearDatabase) {
        // 清空消息列表
        chatRepository.clearAllMessages(clearDatabase);
        
        // 重置UI状态
        isSearchMode.postValue(false);
        searchKeyword.postValue("");
        messageInput.postValue("");
        isLoading.postValue(false);
        
        // 清除错误和成功消息
        errorMessage.postValue(null);
        successMessage.postValue(null);
        
        // 重置同步状态
        syncStatusMessage.postValue(null);
        syncProgress.postValue(0);
        
        // 重置离线状态
        pendingMessagesCount.postValue(0);
        failedMessagesCount.postValue(0);
        offlineStatusMessage.postValue(null);
    }
    
    /**
     * 清除成功消息
     */
    public void clearSuccessMessage() {
        successMessage.postValue(null);
    }
    
    /**
     * 验证消息内容
     * @param content 消息内容
     * @return 是否有效
     */
    public boolean isValidMessageContent(String content) {
        return content != null && !content.trim().isEmpty() && content.trim().length() <= 500;
    }
    
    /**
     * 获取当前时间戳
     * @return 格式化的时间戳
     */
    public String getCurrentTimestamp() {
        return timeFormatter.format(new Date());
    }
    
    // ==================== 生命周期管理 ====================
    
    /**
     * 手动清理资源
     * 在Fragment或Activity销毁时调用
     */
    public void cleanup() {
        try {
            // 移除所有observeForever监听器
            if (networkStateManager != null && networkObserver != null) {
                networkStateManager.getNetworkAvailability().removeObserver(networkObserver);
            }
            if (offlineService != null && offlineStatusObserver != null) {
                offlineService.getOfflineStatus().removeObserver(offlineStatusObserver);
            }
            if (offlineCacheManager != null && cacheStatsObserver != null) {
                offlineCacheManager.getCacheStats().removeObserver(cacheStatsObserver);
            }
            if (offlineService != null && syncProgressObserver != null) {
                offlineService.getSyncProgress().removeObserver(syncProgressObserver);
            }
            
            // 清理同步服务
            if (syncService != null) {
                syncService.cleanup();
            }
            
            // 清理网络状态管理器
            if (networkStateManager != null) {
                networkStateManager.stopNetworkMonitoring();
            }
            
            // 清理离线服务
            if (offlineService != null) {
                offlineService.cleanup();
            }
            
            // 清理离线缓存管理器
            if (offlineCacheManager != null) {
                offlineCacheManager.cleanup();
            }
            
            // 清空所有LiveData
            isSearchMode.postValue(false);
            searchKeyword.postValue("");
            messageInput.postValue("");
            isLoading.postValue(false);
            errorMessage.postValue(null);
            successMessage.postValue(null);
            syncStatusMessage.postValue(null);
            offlineStatusMessage.postValue(null);
            
        } catch (Exception e) {
            // 忽略清理过程中的异常
        }
    }
    
    /**
     * ViewModel被清理时调用
     * 系统自动调用，用于释放资源
     */
    @Override
    protected void onCleared() {
        super.onCleared();
        cleanup();
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 检查是否处于搜索模式
     * @return 是否处于搜索模式
     */
    public boolean isInSearchMode() {
        Boolean searchMode = isSearchMode.getValue();
        return searchMode != null && searchMode;
    }
    
    /**
     * 获取当前搜索关键词
     * @return 当前搜索关键词
     */
    public String getCurrentSearchKeyword() {
        String keyword = searchKeyword.getValue();
        return keyword != null ? keyword : "";
    }
    
    /**
     * 获取当前消息输入内容
     * @return 当前消息输入内容
     */
    public String getCurrentMessageInput() {
        String input = messageInput.getValue();
        return input != null ? input : "";
    }
    
    /**
     * 检查云端同步是否启用
     * @return 是否启用云端同步
     */
    public boolean isCloudSyncEnabled() {
        Boolean enabled = isCloudSyncEnabled.getValue();
        return enabled != null && enabled;
    }
    
    /**
     * 检查网络是否可用
     * @return 网络是否可用
     */
    public boolean isNetworkAvailable() {
        Boolean available = isNetworkAvailable.getValue();
        return available != null && available;
    }
    
    /**
     * ViewModel工厂类
     * 用于创建带有自定义参数的ChatViewModel实例
     */
    public static class Factory implements ViewModelProvider.Factory {
        private final Application application;
        private final ChatRepository repository;
        
        public Factory(@NonNull Application application, @NonNull ChatRepository repository) {
            this.application = application;
            this.repository = repository;
        }
        
        public Factory(@NonNull ChatRepository repository) {
            this.application = null;
            this.repository = repository;
        }
        
        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(ChatViewModel.class)) {
                if (application != null) {
                    return (T) new ChatViewModel(application, repository);
                } else {
                    // 如果没有提供Application，尝试从Repository获取Context
                    // 这里需要根据你的Repository实现来调整
                    throw new IllegalArgumentException("Application is required for ChatViewModel");
                }
            }
            throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
        }
    }

    /**
     * 监听网络状态和离线缓存状态
     */
    // 存储Observer引用以便清理
    private androidx.lifecycle.Observer<Boolean> networkObserver;
    private androidx.lifecycle.Observer<OfflineService.OfflineStatus> offlineStatusObserver;
    private androidx.lifecycle.Observer<OfflineCacheManager.CacheStats> cacheStatsObserver;
    private androidx.lifecycle.Observer<OfflineService.SyncProgress> syncProgressObserver;
    
    private void observeNetworkAndOfflineStatus() {
        // 监听网络状态变化
        networkObserver = isConnected -> {
            isNetworkAvailable.postValue(isConnected);
            
            if (isConnected) {
                syncStatusMessage.postValue("网络已连接，正在同步数据...");
                // 网络恢复时自动同步
                if (isCloudSyncEnabled.getValue() != null && isCloudSyncEnabled.getValue()) {
                    // 检查OfflineService是否仍然可用
                    try {
                        offlineService.syncPendingMessages();
                    } catch (Exception e) {
                        // 忽略已关闭的ExecutorService异常
                    }
                }
            } else {
                syncStatusMessage.postValue("网络已断开，消息将缓存到本地");
            }
        };
        networkStateManager.getNetworkAvailability().observeForever(networkObserver);
        
        // 监听离线服务状态
        offlineStatusObserver = status -> {
            switch (status.getStatus()) {
                case ONLINE:
                    offlineStatusMessage.postValue("在线模式");
                    break;
                case OFFLINE:
                    offlineStatusMessage.postValue("离线模式 - 消息已缓存");
                    break;
                case SYNCING:
                    offlineStatusMessage.postValue("正在同步缓存的消息...");
                    break;
                case SYNC_FAILED:
                    offlineStatusMessage.postValue("同步失败: " + status.getErrorMessage());
                    break;
            }
        };
        offlineService.getOfflineStatus().observeForever(offlineStatusObserver);
        
        // 监听缓存统计信息
        cacheStatsObserver = stats -> {
            pendingMessagesCount.postValue(stats.getPendingCount());
            failedMessagesCount.postValue(stats.getFailedCount());
        };
        offlineCacheManager.getCacheStats().observeForever(cacheStatsObserver);
        
        // 监听同步进度
        syncProgressObserver = progress -> {
            syncProgress.postValue(progress.getProgressPercentage());
            if (progress.getCurrentOperation() != null) {
                syncStatusMessage.postValue(progress.getCurrentOperation());
            }
        };
        offlineService.getSyncProgress().observeForever(syncProgressObserver);
    }
    
    /**
     * 获取待同步消息数量的LiveData
     * @return 待同步消息数量
     */
    public LiveData<Integer> getPendingMessagesCount() {
        return pendingMessagesCount;
    }
    
    /**
     * 获取同步失败消息数量的LiveData
     * @return 同步失败消息数量
     */
    public LiveData<Integer> getFailedMessagesCount() {
        return failedMessagesCount;
    }
    
    /**
     * 获取离线状态消息的LiveData
     * @return 离线状态消息
     */
    public LiveData<String> getOfflineStatusMessage() {
        return offlineStatusMessage;
    }
    
    /**
     * 获取网络状态的LiveData
     * @return 网络状态
     */
    public LiveData<Boolean> getNetworkState() {
        return networkStateManager.getNetworkAvailability();
    }
    
    /**
     * 获取离线服务状态的LiveData
     * @return 离线服务状态
     */
    public LiveData<OfflineService.OfflineStatus> getOfflineServiceStatus() {
        return offlineService.getOfflineStatus();
    }
    
    /**
     * 获取缓存统计信息的LiveData
     * @return 缓存统计信息
     */
    public LiveData<OfflineCacheManager.CacheStats> getCacheStats() {
        return offlineCacheManager.getCacheStats();
    }
    
    /**
     * 重试失败的同步操作
     */
    public void retryFailedSync() {
        if (isNetworkAvailable.getValue() != null && isNetworkAvailable.getValue()) {
            offlineService.retryFailedMessages();
            successMessage.postValue("正在重试失败的消息同步...");
        } else {
            errorMessage.postValue("网络不可用，无法重试同步");
        }
    }
    
    /**
     * 清除离线缓存
     */
    public void clearCache() {
        offlineCacheManager.clearCache();
        successMessage.postValue("缓存已清除");
    }
    
    /**
     * 获取缓存大小
     * @return 缓存大小（字节）
     */
    public long getCacheSize() {
        return offlineCacheManager.getCacheSize();
    }
    
    /**
     * 设置自动同步开关
     * @param enabled 是否启用自动同步
     */
    public void setAutoSyncEnabled(boolean enabled) {
        offlineService.setAutoSyncEnabled(enabled);
        if (enabled) {
            successMessage.postValue("自动同步已启用");
        } else {
            successMessage.postValue("自动同步已禁用");
        }
    }
    
    /**
     * 检查是否有待同步的消息
     * @return 是否有待同步的消息
     */
    public boolean hasPendingMessages() {
        Integer count = pendingMessagesCount.getValue();
        return count != null && count > 0;
    }
    
    /**
     * 检查是否有同步失败的消息
     * @return 是否有同步失败的消息
     */
    public boolean hasFailedMessages() {
        Integer count = failedMessagesCount.getValue();
        return count != null && count > 0;
    }
}