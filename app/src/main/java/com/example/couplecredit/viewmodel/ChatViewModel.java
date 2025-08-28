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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天界面ViewModel
 * 负责管理聊天界面的业务逻辑和UI状态
 * 实现MVVM架构中的ViewModel层，连接View和Repository
 */
public class ChatViewModel extends AndroidViewModel {
    
    // ==================== 成员变量 ====================
    
    private final ChatRepository chatRepository;
    
    // UI状态相关的LiveData
    private final MutableLiveData<Boolean> isSearchMode = new MutableLiveData<>(false);
    private final MutableLiveData<String> searchKeyword = new MutableLiveData<>("");
    private final MutableLiveData<String> messageInput = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String> successMessage = new MutableLiveData<>();
    
    // 时间格式化器
    private final SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm", Locale.getDefault());
    
    // ==================== 构造函数 ====================
    
    /**
     * 构造函数
     * @param application 应用程序实例
     * @param repository 数据仓库实例
     */
    public ChatViewModel(@NonNull Application application, @NonNull ChatRepository repository) {
        super(application);
        this.chatRepository = repository;
        
        // 初始化加载消息
        loadMessages();
    }
    
    /**
     * 原有构造函数（保持兼容性）
     * @param application 应用程序实例
     */
    public ChatViewModel(@NonNull Application application) {
        super(application);
        chatRepository = new ChatRepository(application);
        
        // 初始化加载消息
        loadMessages();
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
        // 加载消息数据
        loadMessages();
        
        // 重置UI状态
        isSearchMode.setValue(false);
        searchKeyword.setValue("");
        messageInput.setValue("");
        isLoading.setValue(false);
        
        // 清除之前的错误和成功消息
        errorMessage.setValue(null);
        successMessage.setValue(null);
    }


    /**
     * 加载消息列表
     */
    public void loadMessages() {
        isLoading.setValue(true);
        chatRepository.loadAllMessages();
        chatRepository.saveDefaultMessages();
        isLoading.setValue(false);
    }
    
    /**
     * 发送消息
     * @param content 消息内容
     */
    public void sendMessage(String content) {
        if (content == null || content.trim().isEmpty()) {
            errorMessage.setValue("消息内容不能为空");
            return;
        }
        
        isLoading.setValue(true);
        
        // 创建新消息
        String timestamp = timeFormatter.format(new Date());
        ChatMessage newMessage = new ChatMessage(
            "我", 
            content.trim(), 
            timestamp, 
            android.R.drawable.ic_dialog_info, 
            true
        );
        
        // 保存到数据库
        chatRepository.insertMessage(newMessage, new ChatRepository.InsertCallback() {
            @Override
            public void onSuccess(long id) {
                isLoading.postValue(false);
                messageInput.postValue(""); // 清空输入框
                successMessage.postValue("消息发送成功");
            }
            
            @Override
            public void onError(Exception e) {
                isLoading.postValue(false);
                errorMessage.postValue("消息发送失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 搜索消息
     * @param keyword 搜索关键词
     */
    public void searchMessages(String keyword) {
        searchKeyword.setValue(keyword);
        
        if (keyword == null || keyword.trim().isEmpty()) {
            // 退出搜索模式
            exitSearchMode();
            return;
        }
        
        // 进入搜索模式
        isSearchMode.setValue(true);
        chatRepository.searchMessages(keyword.trim());
    }
    
    /**
     * 退出搜索模式
     */
    public void exitSearchMode() {
        isSearchMode.setValue(false);
        searchKeyword.setValue("");
        // 重新加载所有消息
        loadMessages();
    }
    
    /**
     * 切换消息点赞状态
     * @param message 要切换点赞状态的消息
     */
    public void toggleMessageLike(ChatMessage message) {
        if (message == null) {
            errorMessage.setValue("消息不存在");
            return;
        }
        
        // 切换点赞状态
        message.setLiked(!message.isLiked());
        
        // 更新数据库
        chatRepository.updateMessage(message, new ChatRepository.UpdateCallback() {
            @Override
            public void onSuccess() {
                String status = message.isLiked() ? "点赞" : "取消点赞";
                successMessage.postValue(status + "成功");
            }
            
            @Override
            public void onError(Exception e) {
                // 回滚状态
                message.setLiked(!message.isLiked());
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
            errorMessage.setValue("消息不存在");
            return;
        }
        
        isLoading.setValue(true);
        
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
     * 复制消息内容
     * @param message 要复制的消息
     * @return 复制的内容
     */
    public String copyMessageContent(ChatMessage message) {
        if (message == null || message.getContent() == null) {
            errorMessage.setValue("消息内容为空");
            return "";
        }
        
        successMessage.setValue("消息已复制到剪贴板");
        return message.getContent();
    }
    
    /**
     * 设置消息输入内容
     * @param input 输入内容
     */
    public void setMessageInput(String input) {
        messageInput.setValue(input);
    }
    
    /**
     * 清除错误消息
     */
    public void clearErrorMessage() {
        errorMessage.setValue(null);
    }
    
    /**
     * 清除成功消息
     */
    public void clearSuccessMessage() {
        successMessage.setValue(null);
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
     * 用于Fragment中手动调用清理
     */
    public void cleanup() {
        // 清理Repository资源
        if (chatRepository != null) {
            chatRepository.cleanup();
        }
        
        // 清理UI状态
        isSearchMode.setValue(false);
        searchKeyword.setValue("");
        messageInput.setValue("");
        isLoading.setValue(false);
        errorMessage.setValue(null);
        successMessage.setValue(null);
    }

    /**
     * ViewModel被清理时调用
     */
    @Override
    protected void onCleared() {
        super.onCleared();
        // 清理Repository资源
        if (chatRepository != null) {
            chatRepository.cleanup();
        }
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
}