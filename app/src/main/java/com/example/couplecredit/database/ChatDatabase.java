package com.example.couplecredit.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

/**
 * 聊天数据库类
 * 使用Room框架管理SQLite数据库
 */
@Database(
    entities = {ChatMessageEntity.class},  // 数据库包含的实体类
    version = 7,                           // 数据库版本号
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
     * 数据库迁移：从版本1到版本2
     * 添加新字段：relationshipId, userId, messageType, avatarUri, isLiked, 
     * isSentByMe, isDeleted, createdAt, updatedAt, cloudMessageId, syncStatus
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 重新创建表以确保所有字段都正确定义
            database.execSQL("CREATE TABLE IF NOT EXISTS chat_messages_new (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "relationshipId INTEGER NOT NULL DEFAULT 0, " +
                "userId INTEGER NOT NULL DEFAULT 0, " +
                "username TEXT, " +
                "content TEXT, " +
                "messageType TEXT NOT NULL DEFAULT 'text', " +
                "timestamp TEXT, " +
                "avatarResId INTEGER NOT NULL DEFAULT 0, " +
                "avatarUri TEXT, " +
                "isLiked INTEGER NOT NULL DEFAULT 0, " +
                "isSentByMe INTEGER NOT NULL DEFAULT 0, " +
                "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                "createdAt INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "cloudMessageId INTEGER, " +
                "syncStatus INTEGER NOT NULL DEFAULT 0" +
                ")");
            
            // 复制现有数据到新表（只复制确实存在的字段）
            try {
                database.execSQL("INSERT INTO chat_messages_new (id, content, timestamp, avatarResId, isSentByMe, isLiked) " +
                    "SELECT id, content, timestamp, avatarResId, isSentByMe, isLiked FROM chat_messages");
            } catch (Exception e) {
                // 如果复制失败，尝试只复制基本字段
                try {
                    database.execSQL("INSERT INTO chat_messages_new (id, content, timestamp, avatarResId) " +
                        "SELECT id, content, timestamp, avatarResId FROM chat_messages");
                } catch (Exception e2) {
                    // 如果还是失败，只复制最基本的字段
                    database.execSQL("INSERT INTO chat_messages_new (content) " +
                        "SELECT content FROM chat_messages");
                }
            }
            
            // 删除旧表
            database.execSQL("DROP TABLE chat_messages");
            
            // 重命名新表
            database.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages");
            
            // 更新现有记录的时间戳
            long currentTime = System.currentTimeMillis();
            database.execSQL("UPDATE chat_messages SET createdAt = " + currentTime + ", updatedAt = " + currentTime + " WHERE createdAt = 0");
        }
        
        /**
         * 安全地添加列（如果列不存在）
         */
        private void addColumnIfNotExists(SupportSQLiteDatabase database, String tableName, String columnName, String columnDefinition) {
            try {
                // 使用PRAGMA table_info检查列是否存在
                android.database.Cursor cursor = database.query("PRAGMA table_info(" + tableName + ")");
                boolean columnExists = false;
                
                if (cursor != null) {
                    try {
                        // PRAGMA table_info返回的列：cid, name, type, notnull, dflt_value, pk
                        // name列的索引是1
                        while (cursor.moveToNext()) {
                            String existingColumnName = cursor.getString(1); // name列在索引1
                            if (columnName.equals(existingColumnName)) {
                                columnExists = true;
                                break;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
                
                // 如果列不存在，则添加
                if (!columnExists) {
                    database.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                }
                
            } catch (Exception e) {
                // 如果检查失败，尝试直接添加列（可能会失败但不会崩溃）
                try {
                    database.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                } catch (Exception addException) {
                    // 忽略添加列失败的异常，可能是因为列已存在
                    android.util.Log.w("ChatDatabase", "Failed to add column " + columnName + ": " + addException.getMessage());
                }
            }
        }
    };
    
    /**
     * 数据库迁移：从版本2到版本3
     * 重新创建表以确保结构正确
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 直接删除旧表并重新创建
            database.execSQL("DROP TABLE IF EXISTS chat_messages");
            
            // 创建新的表结构
            database.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "relationshipId INTEGER NOT NULL DEFAULT 0, " +
                "userId INTEGER NOT NULL DEFAULT 0, " +
                "username TEXT, " +
                "content TEXT, " +
                "messageType TEXT NOT NULL DEFAULT 'text', " +
                "timestamp TEXT, " +
                "avatarResId INTEGER NOT NULL DEFAULT 0, " +
                "avatarUri TEXT, " +
                "isLiked INTEGER NOT NULL DEFAULT 0, " +
                "isSentByMe INTEGER NOT NULL DEFAULT 0, " +
                "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                "createdAt INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "cloudMessageId INTEGER, " +
                "syncStatus INTEGER NOT NULL DEFAULT 0" +
                ")");
        }
    };
    
    /**
     * 数据库迁移：从版本3到版本4
     * 使用fallbackToDestructiveMigration确保数据库能正常工作
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                // 删除旧表并重新创建，确保结构完全正确
                database.execSQL("DROP TABLE IF EXISTS chat_messages");
                
                // 重新创建表，确保与Entity完全匹配
                database.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "relationshipId INTEGER NOT NULL DEFAULT 0, " +
                    "userId INTEGER NOT NULL DEFAULT 0, " +
                    "username TEXT, " +
                    "content TEXT, " +
                    "messageType TEXT NOT NULL DEFAULT 'text', " +
                    "timestamp TEXT, " +
                    "avatarResId INTEGER NOT NULL DEFAULT 0, " +
                    "avatarUri TEXT, " +
                    "isLiked INTEGER NOT NULL DEFAULT 0, " +
                    "isSentByMe INTEGER NOT NULL DEFAULT 0, " +
                    "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "cloudMessageId INTEGER, " +
                    "syncStatus INTEGER NOT NULL DEFAULT 0" +
                    ")");
                
                android.util.Log.d("ChatDatabase", "MIGRATION_3_4: 表结构重置成功");
            } catch (Exception e) {
                android.util.Log.e("ChatDatabase", "MIGRATION_3_4 失败", e);
                throw e;
            }
        }
    };
    
    /**
     * 数据库迁移：从版本4到版本5
     * 更彻底的迁移策略，确保数据库能正常工作
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                // 完全重置数据库结构，确保与最新Entity定义完全一致
                database.execSQL("DROP TABLE IF EXISTS chat_messages");
                
                // 创建最新的表结构
                database.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "relationshipId INTEGER NOT NULL DEFAULT 0, " +
                    "userId INTEGER NOT NULL DEFAULT 0, " +
                    "username TEXT, " +
                    "content TEXT, " +
                    "messageType TEXT NOT NULL DEFAULT 'text', " +
                    "timestamp TEXT, " +
                    "avatarResId INTEGER NOT NULL DEFAULT 0, " +
                    "avatarUri TEXT, " +
                    "isLiked INTEGER NOT NULL DEFAULT 0, " +
                    "isSentByMe INTEGER NOT NULL DEFAULT 0, " +
                    "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "cloudMessageId INTEGER, " +
                    "syncStatus INTEGER NOT NULL DEFAULT 0" +
                    ")");
                
                android.util.Log.d("ChatDatabase", "MIGRATION_4_5: 数据库结构完全重置成功");
            } catch (Exception e) {
                android.util.Log.e("ChatDatabase", "MIGRATION_4_5 失败", e);
                // 不抛出异常，让fallbackToDestructiveMigration处理
            }
        }
    };
    
    /**
     * 数据库迁移：从版本5到版本6
     * 添加账单相关字段：billId 和 isBillCandidate
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                // 添加新字段：billId 和 isBillCandidate
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN billId INTEGER");
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN isBillCandidate INTEGER NOT NULL DEFAULT 0");
                
                android.util.Log.d("ChatDatabase", "MIGRATION_5_6: 成功添加账单相关字段");
            } catch (Exception e) {
                android.util.Log.e("ChatDatabase", "MIGRATION_5_6 失败，使用破坏性迁移", e);
                // 如果添加字段失败，重新创建整个表
                database.execSQL("DROP TABLE IF EXISTS chat_messages");
                
                database.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "relationshipId INTEGER NOT NULL DEFAULT 0, " +
                    "userId INTEGER NOT NULL DEFAULT 0, " +
                    "username TEXT, " +
                    "content TEXT, " +
                    "messageType TEXT NOT NULL DEFAULT 'text', " +
                    "timestamp TEXT, " +
                    "avatarResId INTEGER NOT NULL DEFAULT 0, " +
                    "avatarUri TEXT, " +
                    "isLiked INTEGER NOT NULL DEFAULT 0, " +
                    "isSentByMe INTEGER NOT NULL DEFAULT 0, " +
                    "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "cloudMessageId INTEGER, " +
                    "syncStatus INTEGER NOT NULL DEFAULT 0, " +
                    "billId INTEGER, " +
                    "isBillCandidate INTEGER NOT NULL DEFAULT 0" +
                    ")");
            }
        }
    };

    /**
     * 数据库迁移：从版本6到版本7
     * 为高频查询字段添加索引（纯加索引，不 DROP 表，不丢数据）
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // CREATE INDEX IF NOT EXISTS：幂等，重复执行不报错
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_createdAt` ON `chat_messages` (`createdAt`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_cloudMessageId` ON `chat_messages` (`cloudMessageId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_username` ON `chat_messages` (`username`)");
            android.util.Log.d("ChatDatabase", "MIGRATION_6_7: 成功添加查询索引");
        }
    };

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)  // 添加数据库迁移策略
                    .fallbackToDestructiveMigration()  // 仅 dev 兜底：迁移失败时清库（生产环境长期应移除）
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