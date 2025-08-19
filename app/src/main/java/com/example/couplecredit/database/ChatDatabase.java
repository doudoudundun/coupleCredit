package com.example.couplecredit.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * 聊天数据库类
 * 使用Room框架管理SQLite数据库
 */
@Database(
    entities = {ChatMessageEntity.class},  // 数据库包含的实体类
    version = 1,                           // 数据库版本号
    exportSchema = false                   // 不导出数据库架构
)
public abstract class ChatDatabase extends RoomDatabase {
    
    /**
     * 数据库实例（单例模式）
     */
    private static volatile ChatDatabase INSTANCE;
    
    /**
     * 数据库名称
     */
    private static final String DATABASE_NAME = "chat_database";
    
    /**
     * 获取聊天消息DAO
     * @return ChatMessageDao实例
     */
    public abstract ChatMessageDao chatMessageDao();
    
    /**
     * 获取数据库实例（单例模式）
     * @param context 应用上下文
     * @return ChatDatabase实例
     */
    public static ChatDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ChatDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        ChatDatabase.class,
                        DATABASE_NAME
                    )
                    .allowMainThreadQueries()  // 允许在主线程执行查询（仅用于简化示例，生产环境建议使用异步）
                    .build();
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * 关闭数据库连接
     */
    public static void destroyInstance() {
        if (INSTANCE != null) {
            INSTANCE.close();
            INSTANCE = null;
        }
    }
}