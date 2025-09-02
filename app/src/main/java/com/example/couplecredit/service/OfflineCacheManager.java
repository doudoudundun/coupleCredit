package com.example.couplecredit.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.couplecredit.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线缓存管理器
 * 负责管理离线状态下的数据缓存，包括待同步消息队列、同步失败消息管理等
 */
public class OfflineCacheManager {
    
    private static final String PREF_NAME = "offline_cache";
    private static final String KEY_PENDING_MESSAGES = "pending_messages";
    private static final String KEY_FAILED_MESSAGES = "failed_messages";
    private static final String KEY_CACHE_SIZE = "cache_size";
    
    // ==================== 单例模式 ====================
    private static volatile OfflineCacheManager instance;
    
    /**
     * 获取单例实例
     * @param context 应用程序上下文
     * @return OfflineCacheManager实例
     */
    public static OfflineCacheManager getInstance(Context context) {
        if (instance == null) {
            synchronized (OfflineCacheManager.class) {
                if (instance == null) {
                    instance = new OfflineCacheManager(context);
                }
            }
        }
        return instance;
    }
    
    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson;
    private final ExecutorService executor;
    
    // 缓存数据
    private final List<ChatMessage> pendingMessages = new ArrayList<>();
    private final List<ChatMessage> failedMessages = new ArrayList<>();
    
    // LiveData
    private final MutableLiveData<CacheStats> cacheStats = new MutableLiveData<>();
    
    /**
     * 私有构造函数
     * @param context 应用程序上下文
     */
    private OfflineCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.executor = Executors.newSingleThreadExecutor();
        
        loadCacheFromStorage();
        updateCacheStats();
    }
    
    /**
     * 添加待同步消息
     * @param message 待同步的消息
     */
    public void addPendingMessage(ChatMessage message) {
        executor.execute(() -> {
            synchronized (pendingMessages) {
                pendingMessages.add(message);
                savePendingMessages();
                updateCacheStats();
            }
        });
    }
    
    /**
     * 获取所有待同步消息
     * @return 待同步消息列表
     */
    public List<ChatMessage> getPendingMessages() {
        synchronized (pendingMessages) {
            return new ArrayList<>(pendingMessages);
        }
    }
    
    /**
     * 移除已同步的消息
     * @param message 已同步的消息
     */
    public void removePendingMessage(ChatMessage message) {
        executor.execute(() -> {
            synchronized (pendingMessages) {
                Iterator<ChatMessage> iterator = pendingMessages.iterator();
                while (iterator.hasNext()) {
                    ChatMessage pendingMessage = iterator.next();
                    if (pendingMessage.getId() == message.getId()) {
                        iterator.remove();
                        break;
                    }
                }
                savePendingMessages();
                updateCacheStats();
            }
        });
    }
    
    /**
     * 添加同步失败的消息
     * @param message 同步失败的消息
     */
    public void addFailedMessage(ChatMessage message) {
        executor.execute(() -> {
            synchronized (failedMessages) {
                // 避免重复添加
                boolean exists = false;
                for (ChatMessage failedMessage : failedMessages) {
                    if (failedMessage.getId() == message.getId()) {
                        exists = true;
                        break;
                    }
                }
                
                if (!exists) {
                    failedMessages.add(message);
                    saveFailedMessages();
                    updateCacheStats();
                }
            }
        });
    }
    
    /**
     * 获取所有同步失败的消息
     * @return 同步失败的消息列表
     */
    public List<ChatMessage> getFailedMessages() {
        synchronized (failedMessages) {
            return new ArrayList<>(failedMessages);
        }
    }
    
    /**
     * 移除同步失败的消息（重试成功后）
     * @param message 重试成功的消息
     */
    public void removeFailedMessage(ChatMessage message) {
        executor.execute(() -> {
            synchronized (failedMessages) {
                Iterator<ChatMessage> iterator = failedMessages.iterator();
                while (iterator.hasNext()) {
                    ChatMessage failedMessage = iterator.next();
                    if (failedMessage.getId() == message.getId()) {
                        iterator.remove();
                        break;
                    }
                }
                saveFailedMessages();
                updateCacheStats();
            }
        });
    }
    
    /**
     * 清除所有缓存数据
     */
    public void clearAllCache() {
        executor.execute(() -> {
            synchronized (pendingMessages) {
                pendingMessages.clear();
                savePendingMessages();
            }
            
            synchronized (failedMessages) {
                failedMessages.clear();
                saveFailedMessages();
            }
            
            updateCacheStats();
        });
    }
    
    /**
     * 清除同步失败的消息
     */
    public void clearFailedMessages() {
        executor.execute(() -> {
            synchronized (failedMessages) {
                failedMessages.clear();
                saveFailedMessages();
                updateCacheStats();
            }
        });
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存统计信息的LiveData
     */
    public LiveData<CacheStats> getCacheStats() {
        return cacheStats;
    }
    
    /**
     * 从存储中加载缓存数据
     */
    private void loadCacheFromStorage() {
        executor.execute(() -> {
            // 加载待同步消息
            String pendingJson = preferences.getString(KEY_PENDING_MESSAGES, "");
            if (!pendingJson.isEmpty()) {
                Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
                List<ChatMessage> loadedPending = gson.fromJson(pendingJson, listType);
                if (loadedPending != null) {
                    synchronized (pendingMessages) {
                        pendingMessages.clear();
                        pendingMessages.addAll(loadedPending);
                    }
                }
            }
            
            // 加载同步失败消息
            String failedJson = preferences.getString(KEY_FAILED_MESSAGES, "");
            if (!failedJson.isEmpty()) {
                Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
                List<ChatMessage> loadedFailed = gson.fromJson(failedJson, listType);
                if (loadedFailed != null) {
                    synchronized (failedMessages) {
                        failedMessages.clear();
                        failedMessages.addAll(loadedFailed);
                    }
                }
            }
            
            updateCacheStats();
        });
    }
    
    /**
     * 保存待同步消息到存储
     */
    private void savePendingMessages() {
        String json = gson.toJson(pendingMessages);
        preferences.edit().putString(KEY_PENDING_MESSAGES, json).apply();
    }
    
    /**
     * 保存同步失败消息到存储
     */
    private void saveFailedMessages() {
        String json = gson.toJson(failedMessages);
        preferences.edit().putString(KEY_FAILED_MESSAGES, json).apply();
    }
    
    /**
     * 更新缓存统计信息
     */
    private void updateCacheStats() {
        int pendingCount = pendingMessages.size();
        int failedCount = failedMessages.size();
        long cacheSize = calculateCacheSize();
        
        CacheStats stats = new CacheStats(pendingCount, failedCount, cacheSize);
        cacheStats.postValue(stats);
        
        // 保存缓存大小到SharedPreferences
        preferences.edit().putLong(KEY_CACHE_SIZE, cacheSize).apply();
    }
    
    /**
     * 计算缓存大小（字节）
     * @return 缓存大小
     */
    private long calculateCacheSize() {
        String pendingJson = gson.toJson(pendingMessages);
        String failedJson = gson.toJson(failedMessages);
        return pendingJson.getBytes().length + failedJson.getBytes().length;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
    
    // ==================== 新增：ChatViewModel需要的方法 ====================
    
    /**
     * 清除缓存（ChatViewModel调用的方法）
     * 等同于clearAllCache()方法
     */
    public void clearCache() {
        clearAllCache();
    }
    
    /**
     * 获取缓存大小（ChatViewModel调用的方法）
     * @return 缓存大小（字节）
     */
    public long getCacheSize() {
        return calculateCacheSize();
    }
    
    /**
     * 缓存统计信息类
     */
    public static class CacheStats {
        private final int pendingCount;
        private final int failedCount;
        private final long cacheSize;
        
        public CacheStats(int pendingCount, int failedCount, long cacheSize) {
            this.pendingCount = pendingCount;
            this.failedCount = failedCount;
            this.cacheSize = cacheSize;
        }
        
        public int getPendingCount() {
            return pendingCount;
        }
        
        public int getFailedCount() {
            return failedCount;
        }
        
        public long getCacheSize() {
            return cacheSize;
        }
        
        public int getTotalCount() {
            return pendingCount + failedCount;
        }
        
        public String getCacheSizeFormatted() {
            if (cacheSize < 1024) {
                return cacheSize + " B";
            } else if (cacheSize < 1024 * 1024) {
                return String.format("%.1f KB", cacheSize / 1024.0);
            } else {
                return String.format("%.1f MB", cacheSize / (1024.0 * 1024.0));
            }
        }
    }
}