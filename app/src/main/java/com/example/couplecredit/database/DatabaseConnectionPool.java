package com.example.couplecredit.database;

import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 数据库连接池管理类
 * 用于复用数据库连接，减少连接建立时间
 */
public class DatabaseConnectionPool {
    private static final String TAG = "DatabaseConnectionPool";
    
    // 数据库连接配置
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "demodb";
    private static final String DB_USER = "demodb";
    private static final String DB_PASSWORD = "root";
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    // 连接池配置
    private static final int POOL_SIZE = 7; // 连接池大小
    private static final int CONNECTION_TIMEOUT = 10; // 获取连接超时时间（秒）
    
    private static DatabaseConnectionPool instance;
    private final BlockingQueue<Connection> connectionPool;
    private volatile boolean isInitialized = false;
    
    private DatabaseConnectionPool() {
        connectionPool = new ArrayBlockingQueue<>(POOL_SIZE);
    }
    
    /**
     * 获取连接池单例实例
     */
    public static synchronized DatabaseConnectionPool getInstance() {
        if (instance == null) {
            instance = new DatabaseConnectionPool();
        }
        return instance;
    }
    
    /**
     * 初始化连接池（仅加载驱动，不预创建连接）
     */
    public synchronized void initialize() {
        if (isInitialized) {
            return;
        }
        
        try {
            // 加载MySQL驱动
            Class.forName("com.mysql.jdbc.Driver");
            Log.d(TAG, "MySQL驱动加载成功");
            
            isInitialized = true;
            Log.d(TAG, "连接池初始化完成（驱动已加载）");
            
        } catch (Exception e) {
            Log.e(TAG, "连接池初始化失败", e);
            isInitialized = false;
        }
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        if (!isInitialized) {
            initialize();
        }
        
        try {
            Connection connection = connectionPool.poll(CONNECTION_TIMEOUT, TimeUnit.SECONDS);
            
            if (connection == null) {
                Log.w(TAG, "连接池中无可用连接，创建新连接");
                return createNewConnection();
            }
            
            // 检查连接是否有效
            if (connection.isClosed() || !connection.isValid(3)) {
                Log.w(TAG, "连接已失效，创建新连接");
                return createNewConnection();
            }
            
            Log.d(TAG, "从连接池获取连接，剩余连接数: " + connectionPool.size());
            return connection;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("获取连接被中断", e);
        }
    }
    
    /**
     * 归还连接到连接池
     */
    public void returnConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        
        try {
            if (!connection.isClosed() && connection.isValid(3)) {
                if (connectionPool.offer(connection)) {
                    Log.d(TAG, "连接已归还到连接池，当前连接数: " + connectionPool.size());
                } else {
                    // 连接池已满，关闭连接
                    connection.close();
                    Log.d(TAG, "连接池已满，关闭多余连接");
                }
            } else {
                connection.close();
                Log.d(TAG, "连接已失效，直接关闭");
            }
        } catch (SQLException e) {
            Log.e(TAG, "归还连接时发生错误", e);
        }
    }
    
    /**
     * 创建新的数据库连接（带重试机制）
     */
    private Connection createNewConnection() throws SQLException {
        int maxRetries = 3;
        int retryDelay = 1000; // 1秒
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Log.d(TAG, "尝试创建数据库连接 (" + attempt + "/" + maxRetries + "): " + DB_URL);
                
                // 设置连接超时
                DriverManager.setLoginTimeout(10);
                Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                
                // 测试连接有效性
                if (connection.isValid(5)) {
                    Log.d(TAG, "数据库连接创建成功");
                    return connection;
                } else {
                    connection.close();
                    throw new SQLException("连接无效");
                }
                
            } catch (SQLException e) {
                Log.w(TAG, "连接尝试 " + attempt + " 失败: " + e.getMessage());
                
                if (attempt == maxRetries) {
                    Log.e(TAG, "所有连接尝试均失败，可能的原因:");
                    Log.e(TAG, "1. 网络连接问题");
                    Log.e(TAG, "2. 数据库服务器不可达");
                    Log.e(TAG, "3. 认证信息错误");
                    Log.e(TAG, "4. 防火墙阻止连接");
                    throw new SQLException("数据库连接失败，已重试 " + maxRetries + " 次: " + e.getMessage(), e);
                }
                
                // 等待后重试
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("连接重试被中断", ie);
                }
            }
        }
        
        throw new SQLException("未知错误");
    }
    
    /**
     * 关闭连接池
     */
    public synchronized void shutdown() {
        Log.d(TAG, "开始关闭连接池");
        
        while (!connectionPool.isEmpty()) {
            Connection connection = connectionPool.poll();
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    Log.e(TAG, "关闭连接时发生错误", e);
                }
            }
        }
        
        isInitialized = false;
        Log.d(TAG, "连接池已关闭");
    }
    
    /**
     * 连接池预热 - 在后台线程异步执行
     */
    public void warmUp() {
        if (!isInitialized) {
            initialize();
        }
        
        // 在后台线程执行预连接创建，避免阻塞主线程
        new Thread(() -> {
            try {
                int coreConnections = 5; // 预热时创建5个连接
                int currentSize = connectionPool.size();
                
                if (currentSize < coreConnections) {
                    int needCreate = coreConnections - currentSize;
                    Log.d(TAG, "连接池预热：当前 " + currentSize + " 个连接，需补充 " + needCreate + " 个");
                    
                    for (int i = 0; i < needCreate; i++) {
                        try {
                            Connection connection = createNewConnection();
                            if (connectionPool.offer(connection)) {
                                Log.d(TAG, "预热连接 " + (i + 1) + "/" + needCreate + " 创建成功");
                            } else {
                                connection.close();
                                Log.w(TAG, "连接池已满，停止预热");
                                break;
                            }
                        } catch (SQLException e) {
                            Log.w(TAG, "预热连接创建失败: " + e.getMessage());
                        }
                    }
                } else {
                    Log.d(TAG, "连接池预热：当前连接数充足 (" + currentSize + "/" + POOL_SIZE + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "连接池预热异常", e);
            }
        }, "ConnectionPool-WarmUp").start();
        
        Log.d(TAG, "连接池预热已启动（后台执行）");
    }
    
    /**
     * 获取连接池状态信息
     */
    public String getPoolStatus() {
        return String.format("连接池状态 - 可用连接: %d/%d, 已初始化: %s", 
                connectionPool.size(), POOL_SIZE, isInitialized);
    }
}