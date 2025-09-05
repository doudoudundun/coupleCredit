package com.example.couplecredit.utils;

import android.util.Log;
import com.example.couplecredit.database.DatabaseConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 统一的数据库资源管理工具类
 * 用于管理数据库连接、PreparedStatement和ResultSet的关闭
 */
public class DatabaseResourceManager {
    private static final String TAG = "DatabaseResourceManager";

    /**
     * 关闭数据库资源
     * @param connection 数据库连接
     * @param statement PreparedStatement
     * @param resultSet ResultSet
     */
    public static void closeResources(Connection connection, PreparedStatement statement, ResultSet resultSet) {
        closeResultSet(resultSet);
        closeStatement(statement);
        closeConnection(connection);
    }

    /**
     * 关闭数据库资源（不包含ResultSet）
     * @param connection 数据库连接
     * @param statement PreparedStatement
     */
    public static void closeResources(Connection connection, PreparedStatement statement) {
        closeStatement(statement);
        closeConnection(connection);
    }

    /**
     * 关闭数据库连接
     * @param connection 数据库连接
     */
    public static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                DatabaseConnectionPool.getInstance().returnConnection(connection);
                Log.d(TAG, "数据库连接已返回连接池");
            } catch (Exception e) {
                Log.e(TAG, "返回数据库连接到连接池时出错", e);
            }
        }
    }

    /**
     * 关闭PreparedStatement
     * @param statement PreparedStatement
     */
    public static void closeStatement(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
                Log.d(TAG, "PreparedStatement已关闭");
            } catch (SQLException e) {
                Log.e(TAG, "关闭PreparedStatement时出错", e);
            }
        }
    }

    /**
     * 关闭ResultSet
     * @param resultSet ResultSet
     */
    public static void closeResultSet(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
                Log.d(TAG, "ResultSet已关闭");
            } catch (SQLException e) {
                Log.e(TAG, "关闭ResultSet时出错", e);
            }
        }
    }
}