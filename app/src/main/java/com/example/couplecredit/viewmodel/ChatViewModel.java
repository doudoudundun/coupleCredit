package com.example.couplecredit.viewmodel;

import android.app.Application;
import com.example.couplecredit.R;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.repository.ChatRepository;
import com.example.couplecredit.utils.NetworkStateManager;
import com.example.couplecredit.utils.UserInfoManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天界面ViewModel
 * 负责管理聊天界面的业务逻辑和UI状态
 * 实现MVVM架构中的ViewModel层，连接View和Repository
 * 负责本地消息缓存、云端补偿同步和网络状态检测
 */
public class ChatViewModel extends AndroidViewModel {
    
    // ==================== 成员变量 ====================
    
    private final ChatRepository chatRepository;
    private NetworkStateManager networkStateManager;
    
    // UI状态相关的LiveData
    private final MutableLiveData<Boolean> isSearchMode = new MutableLiveData<>(false);
    private final MutableLiveData<String> searchKeyword = new MutableLiveData<>("");
    private final MutableLiveData<String> messageInput = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String> successMessage = new MutableLiveData<>();
    
    private final MutableLiveData<Boolean> isNetworkAvailable = new MutableLiveData<>(true);
    
    // 用户信息
    private int currentUserId = -1; // 从UserInfoManager获取
    private String currentUsername = null;
    private long currentRelationshipId = -1; // 从UserInfoManager获取

    // AI提取相关
    private final MutableLiveData<List<AuthApiModels.AiExtractionItem>> aiExtractions = new MutableLiveData<>();
    private final java.util.concurrent.ExecutorService aiAnalysisPool = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ConcurrentLinkedQueue<AuthApiModels.AiChatMessage> aiAnalysisQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    
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
        this.networkStateManager = NetworkStateManager.getInstance(application);
        this.networkStateManager.startNetworkMonitoring();
        
        // 从UserInfoManager获取用户信息
        initializeUserInfoFromManager(application);
        observeNetworkStatus();
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
        
    }
    
    /**
     * 加载消息列表
     */
    public void loadMessages() {
        isLoading.postValue(true);
        chatRepository.loadAllMessages();
        isLoading.postValue(false);
    }
    
    /**
     * 刷新消息列表（用于下拉刷新）
     */
    public void refreshMessages() {
        isLoading.postValue(true);
        // 重新加载所有消息
        chatRepository.loadAllMessages();
        isLoading.postValue(false);
        successMessage.postValue("消息已刷新");
    }
    
    /**
     * 加载更新的消息（分页加载）
     */
    public void loadNewerMessages() {
        isLoading.postValue(true);
        chatRepository.loadNewerMessages();
        isLoading.postValue(false);
        successMessage.postValue("新消息加载完成");
    }
    
    /**
     * 加载历史消息（分页加载）
     */
    public void loadOlderMessages() {
        isLoading.postValue(true);
        chatRepository.loadOlderMessages();
        isLoading.postValue(false);
        successMessage.postValue("历史消息加载完成");
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
        if (currentUserId <= 0 || currentRelationshipId <= 0) {
            errorMessage.postValue("请先绑定情侣并刷新聊天信息");
            return;
        }
        
        isLoading.postValue(true);
        
        try {
            // 创建消息对象
            String username = currentUsername != null ? currentUsername : "我";
            ChatMessage message = new ChatMessage(
                username,
                currentUserId,
                content.trim(),
                getCurrentTimestamp(),
                R.drawable.ic_default_avatar,
                null,
                true
            );

            // 设置云端相关信息
            message.setRelationshipId(currentRelationshipId);
            message.setMessageType("text");
            
            // 统一由Repository负责本地落盘和云端补偿同步，避免重复维护SharedPreferences离线队列。
            chatRepository.insertMessage(message, new ChatRepository.InsertCallback() {
                @Override
                public void onSuccess(long id) {
                    Boolean networkAvailable = isNetworkAvailable.getValue();
                    successMessage.postValue(Boolean.TRUE.equals(networkAvailable)
                            ? "消息发送成功" : "消息已保存，网络恢复后将自动同步");
                }

                @Override
                public void onError(Exception e) {
                    errorMessage.postValue("发送消息失败: " + e.getMessage());
                }
            });
            
            // 清空输入框
                messageInput.postValue("");
                onMessageSentForAnalysis(System.currentTimeMillis(), currentUsername != null ? currentUsername : "我", content.trim());
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
                        currentUsername = username;
                        currentRelationshipId = relationshipId != null && relationshipId > 0
                                ? relationshipId : -1;
                        android.util.Log.d("ChatViewModel", "从UserInfoManager获取用户信息: userId=" + userId + ", username=" + username + ", relationshipId=" + currentRelationshipId);
                        
                        chatRepository.setCurrentUserId(currentUserId);
                        chatRepository.setCurrentRelationshipId(currentRelationshipId);
                    }
                    
                    @Override
                    public void onError(String error) {
                        android.util.Log.w("ChatViewModel", "获取用户信息失败: " + error);
                        currentRelationshipId = -1;
                        chatRepository.setCurrentRelationshipId(currentRelationshipId);
                    }
                });
            } else {
                android.util.Log.w("ChatViewModel", "UserInfoManager中没有有效的用户ID");
                this.currentUserId = -1;
                this.currentRelationshipId = -1;
            }
        } else {
            android.util.Log.w("ChatViewModel", "用户未登录");
            this.currentUserId = -1;
            this.currentRelationshipId = -1;
        }
        
        // 立即设置基本用户信息到云端Repository
        chatRepository.setCurrentUserId(currentUserId);
        chatRepository.setCurrentRelationshipId(currentRelationshipId);
    }
    
    /**
     * 设置用户信息
     * @param userId 用户ID
     * @param relationshipId 关系ID
     */
    public void setUserInfo(int userId, long relationshipId) {
        this.currentUserId = userId;
        this.currentRelationshipId = relationshipId;
        
        // 更新云端Repository
        chatRepository.setCurrentUserId(userId);
        chatRepository.setCurrentRelationshipId(relationshipId);
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
                        long relId = relationshipId != null && relationshipId > 0
                                ? relationshipId : -1;
                        
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
     * 检查网络状态
     */
    private void checkNetworkStatus() {
        // 这里应该实现实际的网络检查逻辑
        // 可以使用ConnectivityManager或其他网络检测方法
        boolean networkAvailable = chatRepository.isNetworkAvailable();
        isNetworkAvailable.setValue(networkAvailable);
        
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
            if (networkStateManager != null && networkObserver != null) {
                networkStateManager.getNetworkAvailability().removeObserver(networkObserver);
            }
            if (networkStateManager != null) {
                networkStateManager.stopNetworkMonitoring();
            }

            chatRepository.cleanup();
            isSearchMode.postValue(false);
            searchKeyword.postValue("");
            messageInput.postValue("");
            isLoading.postValue(false);
            errorMessage.postValue(null);
            successMessage.postValue(null);
            
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
        aiAnalysisPool.shutdownNow();
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
     * 监听网络状态；网络恢复时复用Repository的单一同步入口。
     */
    private androidx.lifecycle.Observer<Boolean> networkObserver;

    private void observeNetworkStatus() {
        networkObserver = isConnected -> {
            isNetworkAvailable.postValue(isConnected);
            if (Boolean.TRUE.equals(isConnected)
                    && UserInfoManager.isUserLoggedIn(getApplication())) {
                chatRepository.loadAllMessages();
            }
        };
        networkStateManager.getNetworkAvailability().observeForever(networkObserver);
    }

    public LiveData<List<AuthApiModels.AiExtractionItem>> getAiExtractions() { return aiExtractions; }

    public void onMessageSentForAnalysis(long messageId, String username, String content) {
        aiAnalysisQueue.offer(new AuthApiModels.AiChatMessage(messageId, username, content));
        drainAiAnalysisQueue();
    }

    private void drainAiAnalysisQueue() {
        aiAnalysisPool.execute(() -> {
            List<AuthApiModels.AiChatMessage> batch = new ArrayList<>();
            AuthApiModels.AiChatMessage msg;
            while ((msg = aiAnalysisQueue.poll()) != null) {
                batch.add(msg);
            }
            if (batch.isEmpty()) return;

            android.util.Log.d("ChatViewModel", "AI分析: 处理 " + batch.size() + " 条消息");
            AuthApiClient.analyzeAiChat(getApplication(), currentUserId, batch, new AuthApiClient.AiAnalyzeCallback() {
                @Override public void onSuccess(AuthApiModels.AiAnalyzeResponse response) {
                    if (response != null && response.data != null && response.data.extractions != null && !response.data.extractions.isEmpty()) {
                        List<AuthApiModels.AiExtractionItem> current = aiExtractions.getValue();
                        List<AuthApiModels.AiExtractionItem> updated = new ArrayList<>(current != null ? current : java.util.Collections.emptyList());
                        updated.addAll(response.data.extractions);
                        aiExtractions.postValue(updated);
                        android.util.Log.d("ChatViewModel", "AI分析: 发现 " + response.data.extractions.size() + " 个提取");
                    }
                    if (!aiAnalysisQueue.isEmpty()) {
                        drainAiAnalysisQueue();
                    }
                }
                @Override public void onError(String error) {
                    android.util.Log.w("ChatViewModel", "AI分析失败: " + error);
                    if (!aiAnalysisQueue.isEmpty()) {
                        drainAiAnalysisQueue();
                    }
                }
            });
        });
    }

    private void removeExtractionFromLiveData(int extractionId) {
        List<AuthApiModels.AiExtractionItem> current = aiExtractions.getValue();
        if (current != null && !current.isEmpty()) {
            List<AuthApiModels.AiExtractionItem> updated = new ArrayList<>(current);
            updated.removeIf(item -> item.id == extractionId);
            aiExtractions.postValue(updated);
        }
    }

    public void confirmExtraction(int extractionId) {
        confirmExtractionWithOverrides(extractionId, null);
    }

    public void confirmExtractionWithOverrides(int extractionId, java.util.Map<String, Object> overrides) {
        AuthApiClient.confirmAiExtraction(getApplication(), extractionId, currentUserId, overrides, new AuthApiClient.ConfirmCallback() {
            @Override public void onSuccess(AuthApiModels.AiExtractionActionResponse response) {
                removeExtractionFromLiveData(extractionId);
                successMessage.postValue("已记录");
            }
            @Override public void onError(String error) {
                errorMessage.postValue("确认失败: " + error);
            }
        });
    }

    public void dismissExtraction(int extractionId) {
        AuthApiClient.dismissAiExtraction(getApplication(), extractionId, currentUserId, new AuthApiClient.ConfirmCallback() {
            @Override public void onSuccess(AuthApiModels.AiExtractionActionResponse response) {
                removeExtractionFromLiveData(extractionId);
            }
            @Override public void onError(String error) {}
        });
    }
}
