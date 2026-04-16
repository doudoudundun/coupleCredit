package com.example.couplecredit.database;

import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import com.example.couplecredit.config.DatabaseConfig;

/**
 * 数据库连接池管理类
 * 用于复用数据库连接，减少连接建立时间
 */
public class DatabaseConnectionPool {
    private static final String TAG = "DatabaseConnectionPool";
    
    // 使用统一的数据库配置
    
    // 连接池配置
    private static final int POOL_SIZE = 7; // 连接池大小
    private static final int CONNECTION_TIMEOUT = 10; // 获取连接超时时间（秒）
    private static final int CORE_CONNECTIONS = 3; // 核心连接数，始终保持
    private static final long CONNECTION_KEEP_ALIVE_MS = 30 * 60 * 1000; // 连接保活时间30分钟
    private static final long KEEP_ALIVE_CHECK_INTERVAL_MS = 2 * 60 * 1000; // 保活检查间隔2分钟
    
    private static DatabaseConnectionPool instance;
    private final BlockingQueue<Connection> connectionPool;
    private volatile boolean isInitialized = false;
    private volatile boolean isShuttingDown = false;
    private Thread keepAliveThread;
    
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
     * 初始化连接池（加载驱动并预创建核心连接）
     */
    public synchronized void initialize() {
        if (isInitialized) {
            return;
        }

        // 检查是否有数据库配置
        if (DatabaseConfig.DB_HOST == null || DatabaseConfig.DB_HOST.isEmpty()) {
            Log.d(TAG, "DB_HOST 为空，跳过 JDBC 初始化，使用 HTTP API 模式");
            return;
        }

        try {
            // 加载MySQL驱动
            Class.forName("com.mysql.jdbc.Driver");
            Log.d(TAG, "MySQL驱动加载成功");

            // 预创建核心连接
            createCoreConnections();

            // 启动连接保活线程
            startKeepAliveThread();

            isInitialized = true;
            Log.d(TAG, "连接池初始化完成（驱动已加载，核心连接已创建）");

        } catch (Exception e) {
            Log.e(TAG, "连接池初始化失败", e);
            isInitialized = false;
        }
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        // 检查是否有数据库配置
        if (DatabaseConfig.DB_HOST == null || DatabaseConfig.DB_HOST.isEmpty()) {
            throw new SQLException("无数据库配置，请使用 HTTP API 模式");
        }

        if (!isInitialized) {
            initialize();
        }

        try {
            Connection connection = connectionPool.poll(CONNECTION_TIMEOUT, TimeUnit.SECONDS);
            
            if (connection == null) {
                Log.w(TAG, "连接池中无可用连接，创建新连接");
                return createNewConnection();
            }
            
            // 检查连接是否有效（增强验证）
            if (connection.isClosed() || !isConnectionValid(connection)) {
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
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        Log.e(TAG, "关闭多余连接时出错", e);
                    }
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
                Log.d(TAG, "尝试创建数据库连接 (" + attempt + "/" + maxRetries + "): " + DatabaseConfig.DB_URL);
                
                // 设置连接超时
                DriverManager.setLoginTimeout(10);
                Connection connection = DriverManager.getConnection(DatabaseConfig.DB_URL, DatabaseConfig.DB_USER, DatabaseConfig.DB_PASSWORD);
                
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
     * 创建核心连接
     */
    private void createCoreConnections() {
        Log.d(TAG, "开始创建核心连接，目标数量: " + CORE_CONNECTIONS);
        
        for (int i = 0; i < CORE_CONNECTIONS; i++) {
            try {
                Connection connection = createNewConnection();
                if (connectionPool.offer(connection)) {
                    Log.d(TAG, "核心连接 " + (i + 1) + "/" + CORE_CONNECTIONS + " 创建成功");
                } else {
                    connection.close();
                    Log.w(TAG, "连接池已满，停止创建核心连接");
                    break;
                }
            } catch (SQLException e) {
                Log.w(TAG, "创建核心连接失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 启动连接保活线程
     */
    private void startKeepAliveThread() {
        if (keepAliveThread != null && keepAliveThread.isAlive()) {
            return;
        }
        
        keepAliveThread = new Thread(() -> {
            Log.d(TAG, "连接保活线程启动");
            
            while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    // 每2分钟检查一次连接状态
                    Thread.sleep(KEEP_ALIVE_CHECK_INTERVAL_MS);
                    
                    if (isShuttingDown) break;
                    
                    // 检查并维护连接池
                    maintainConnectionPool();
                    
                } catch (InterruptedException e) {
                    Log.d(TAG, "连接保活线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "连接保活线程异常", e);
                }
            }
            
            Log.d(TAG, "连接保活线程结束");
        }, "ConnectionPool-KeepAlive");
        
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }
    
    /**
     * 维护连接池 - 检查连接有效性并补充核心连接
     */
    private void maintainConnectionPool() {
        Log.d(TAG, "开始维护连接池，当前连接数: " + connectionPool.size());
        
        // 检查现有连接的有效性
        List<Connection> validConnections = new ArrayList<>();
        List<Connection> invalidConnections = new ArrayList<>();
        
        // 取出所有连接进行检查
        Connection conn;
        while ((conn = connectionPool.poll()) != null) {
            try {
                if (!conn.isClosed() && isConnectionValid(conn)) {
                    validConnections.add(conn);
                } else {
                    invalidConnections.add(conn);
                }
            } catch (SQLException e) {
                invalidConnections.add(conn);
            }
        }
        
        // 关闭无效连接
        for (Connection invalidConn : invalidConnections) {
            try {
                invalidConn.close();
            } catch (SQLException e) {
                Log.e(TAG, "关闭无效连接时发生错误", e);
            }
        }
        
        // 将有效连接放回连接池
        for (Connection validConn : validConnections) {
            connectionPool.offer(validConn);
        }
        
        int currentValidCount = validConnections.size();
        Log.d(TAG, "连接池维护完成，有效连接: " + currentValidCount + ", 无效连接: " + invalidConnections.size());
        
        // 如果有效连接数少于核心连接数，补充连接
        if (currentValidCount < CORE_CONNECTIONS) {
            int needCreate = CORE_CONNECTIONS - currentValidCount;
            Log.d(TAG, "需要补充 " + needCreate + " 个连接");
            
            for (int i = 0; i < needCreate; i++) {
                try {
                    Connection newConn = createNewConnection();
                    if (connectionPool.offer(newConn)) {
                        Log.d(TAG, "补充连接 " + (i + 1) + "/" + needCreate + " 成功");
                    } else {
                        newConn.close();
                        break;
                    }
                } catch (SQLException e) {
                    Log.w(TAG, "补充连接失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 关闭连接池
     */
    public synchronized void shutdown() {
        Log.d(TAG, "开始关闭连接池");
        
        isShuttingDown = true;
        
        // 停止保活线程
        if (keepAliveThread != null && keepAliveThread.isAlive()) {
            keepAliveThread.interrupt();
            try {
                keepAliveThread.join(5000); // 等待最多5秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // 关闭所有连接
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
     * 增强的连接有效性检查
     */
    private boolean isConnectionValid(Connection connection) {
        try {
            // 首先检查基本状态
            if (connection.isClosed()) {
                return false;
            }
            
            // 使用较短的超时时间进行验证
            if (!connection.isValid(2)) {
                return false;
            }
            
            // 执行简单查询验证连接
            try (PreparedStatement stmt = connection.prepareStatement("SELECT 1")) {
                stmt.setQueryTimeout(3);
                stmt.executeQuery();
                return true;
            }
        } catch (SQLException e) {
            Log.w(TAG, "连接验证失败: " + e.getMessage());
            return false;
        }
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
                int currentSize = connectionPool.size();
                
                if (currentSize < POOL_SIZE) {
                    int needCreate = Math.min(POOL_SIZE - currentSize, POOL_SIZE - CORE_CONNECTIONS);
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