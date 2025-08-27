package com.example.couplecredit.database;

import android.util.Log;

/**
 * 数据库连接池统一初始化工具类
 * 消除各个Helper类中的重复初始化代码
 */
public class DatabaseInitializer {
    private static final String TAG = "DatabaseInitializer";
    private static volatile boolean isInitialized = false;
    
    /**
     * 统一初始化连接池（线程安全）
     * @param callerTag 调用者标识，用于日志记录
     */
    public static void initializeConnectionPool(String callerTag) {
        if (isInitialized) {
            Log.d(TAG, callerTag + " - 连接池已初始化，跳过重复初始化");
            return;
        }
        
        synchronized (DatabaseInitializer.class) {
            if (isInitialized) {
                Log.d(TAG, callerTag + " - 连接池已初始化，跳过重复初始化");
                return;
            }
            
            try {
                DatabaseConnectionPool pool = DatabaseConnectionPool.getInstance();
                pool.initialize();
                pool.warmUp();
                
                isInitialized = true;
                Log.d(TAG, callerTag + " - 连接池初始化完成，" + pool.getPoolStatus());
            } catch (Exception e) {
                Log.e(TAG, callerTag + " - 连接池初始化失败", e);
                throw new RuntimeException("连接池初始化失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 获取连接池状态
     */
    public static String getPoolStatus() {
        if (!isInitialized) {
            return "连接池未初始化";
        }
        return DatabaseConnectionPool.getInstance().getPoolStatus();
    }
    
    /**
     * 检查连接池是否已初始化
     */
    public static boolean isInitialized() {
        return isInitialized;
    }
}