package com.example.couplecredit.config;

/**
 * 统一的数据库配置类
 * 管理所有数据库连接相关的常量
 */
public class DatabaseConfig {
    // 数据库连接配置
    public static final String DB_HOST = "101.37.68.240";
    public static final String DB_PORT = "3306";
    public static final String DB_NAME = "demodb";
    public static final String DB_USER = "demodb";
    public static final String DB_PASSWORD = "root";
    public static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + 
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" +
            "&connectTimeout=10000&socketTimeout=30000&autoReconnect=true" +
            "&maxReconnects=3&initialTimeout=2&testOnBorrow=true" +
            "&validationQuery=SELECT 1&testWhileIdle=true";
    
    // SharedPreferences相关常量
    public static final String PREF_AVATAR_URI = "avatar_uri_";
    
    // 私有构造函数，防止实例化
    private DatabaseConfig() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}