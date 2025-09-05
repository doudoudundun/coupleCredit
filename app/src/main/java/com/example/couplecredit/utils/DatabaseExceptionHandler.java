package com.example.couplecredit.utils;

import android.util.Log;

import java.sql.SQLException;

/**
 * 统一的数据库异常处理工具类
 * 用于处理数据库相关的异常并返回用户友好的错误信息
 */
public class DatabaseExceptionHandler {
    private static final String TAG = "DatabaseExceptionHandler";

    /**
     * 处理数据库异常并返回用户友好的错误信息
     * @param e 异常对象
     * @return 用户友好的错误信息
     */
    public static String handleException(Exception e) {
        Log.e(TAG, "数据库异常: ", e);
        
        if (e instanceof ClassNotFoundException) {
            return "数据库驱动未找到: " + e.getMessage();
        } else if (e instanceof SQLException) {
            SQLException sqlException = (SQLException) e;
            return handleSQLException(sqlException);
        } else {
            return "未知错误: " + e.getMessage();
        }
    }

    /**
     * 处理SQL异常的详细分类
     * @param e SQL异常
     * @return 详细的错误信息
     */
    private static String handleSQLException(SQLException e) {
        String errorCode = String.valueOf(e.getErrorCode());
        String sqlState = e.getSQLState();
        
        // 根据错误代码和SQL状态提供更具体的错误信息
        if (sqlState != null) {
            if (sqlState.startsWith("08")) {
                return "数据库连接失败: " + e.getMessage();
            } else if (sqlState.startsWith("23")) {
                return "数据完整性约束违反: " + e.getMessage();
            } else if (sqlState.startsWith("42")) {
                return "SQL语法错误: " + e.getMessage();
            } else if (sqlState.startsWith("28")) {
                return "数据库访问权限不足: " + e.getMessage();
            }
        }
        
        return "数据库操作失败: " + e.getMessage();
    }

    /**
     * 记录异常信息（用于调试）
     * @param tag 日志标签
     * @param message 日志消息
     * @param e 异常对象
     */
    public static void logException(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }
}