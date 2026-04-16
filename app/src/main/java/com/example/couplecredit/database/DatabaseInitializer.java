package com.example.couplecredit.database;

import android.util.Log;

import com.example.couplecredit.config.DatabaseConfig;

/**
 * 数据库连接池统一初始化工具类
 * 消除各个Helper类中的重复初始化代码
 */
public class DatabaseInitializer {
    private static final String TAG = "DatabaseInitializer";
    private static volatile boolean isInitialized = false;
    private static volatile boolean isSkipped = false; // 是否跳过初始化（无数据库配置）

    /**
     * 检查是否有数据库配置
     * 如果 DB_HOST 为空，说明是外网模式，应使用 HTTP API
     */
    public static boolean hasDatabaseConfig() {
        boolean hasConfig = DatabaseConfig.DB_HOST != null && !DatabaseConfig.DB_HOST.isEmpty();
        Log.d(TAG, "检查数据库配置: DB_HOST='" + DatabaseConfig.DB_HOST + "', hasConfig=" + hasConfig);
        return hasConfig;
    }

    /**
     * 统一初始化连接池（线程安全）
     * @param callerTag 调用者标识，用于日志记录
     */
    public static void initializeConnectionPool(String callerTag) {
        if (isInitialized) {
            Log.d(TAG, callerTag + " - 连接池已初始化，跳过重复初始化");
            return;
        }

        if (isSkipped) {
            Log.d(TAG, callerTag + " - 无数据库配置，已跳过初始化（使用 HTTP API 模式）");
            return;
        }

        // 检查是否有数据库配置
        if (!hasDatabaseConfig()) {
            Log.d(TAG, callerTag + " - DB_HOST 为空，跳过 JDBC 初始化，使用 HTTP API 模式");
            isSkipped = true;
            return;
        }

        synchronized (DatabaseInitializer.class) {
            if (isInitialized || isSkipped) {
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
     * 异步初始化连接池（避免阻塞主线程）
     * @param callerTag 调用者标识，用于日志记录
     */
    public static void initializeConnectionPoolAsync(String callerTag) {
        if (isInitialized || isSkipped) {
            return;
        }

        // 检查是否有数据库配置
        if (!hasDatabaseConfig()) {
            Log.d(TAG, callerTag + " - DB_HOST 为空，跳过 JDBC 初始化，使用 HTTP API 模式");
            isSkipped = true;
            return;
        }

        new Thread(() -> {
            synchronized (DatabaseInitializer.class) {
                if (isInitialized || isSkipped) {
                    return;
                }

                try {
                    DatabaseConnectionPool pool = DatabaseConnectionPool.getInstance();
                    pool.initialize();
                    pool.warmUp();

                    isInitialized = true;
                    Log.d(TAG, callerTag + " - 连接池异步初始化完成，" + pool.getPoolStatus());
                } catch (Exception e) {
                    Log.e(TAG, callerTag + " - 连接池异步初始化失败", e);
                }
            }
        }, "DatabaseInitializer-" + callerTag).start();

        Log.d(TAG, callerTag + " - 连接池异步初始化已启动");
    }

    /**
     * 获取连接池状态
     */
    public static String getPoolStatus() {
        if (isSkipped) {
            return "HTTP API 模式（无 JDBC 连接池）";
        }
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

    /**
     * 检查是否跳过了 JDBC 初始化（使用 HTTP API 模式）
     */
    public static boolean isSkipped() {
        return isSkipped;
    }
}