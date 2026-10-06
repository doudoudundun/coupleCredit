-- 家务 V1 已执行 029 数据库的幂等升级；029 -> 030，先备份再执行。
-- DDL 在 MySQL 隐式提交，不能靠 ROLLBACK 撤销。不要关闭 FOREIGN_KEY_CHECKS。
-- 不更新或删除任何业务行，重复执行结果相同。
-- 已结束周期必须为 NULL，允许同 relationship_id 保留任意多个历史周期。
ALTER TABLE housework_relationship_cycles
  MODIFY COLUMN open_flag TINYINT
    GENERATED ALWAYS AS (IF(ended_at IS NULL, 1, NULL)) STORED;

-- 历史身份与关系 ID 保留原值，但不再依赖可删除的登录主体/关系行。
-- 只解除历史快照表的外部 FK；空间内部 FK、personal owner 和本人偏好 FK 不变。
-- 外键名由 INFORMATION_SCHEMA 取得，兼容 MySQL 自动命名及 DBA 自定义命名。
SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_relationship_cycles'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_relationship_cycles` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_space_members'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_space_members` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_records'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_records` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_record_participants'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_record_participants` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_record_revisions'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_record_revisions` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_config_revisions'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_config_revisions` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

SET @hw_drop_fks = (
  SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `', REPLACE(CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
  FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'housework_mutations'
    AND REFERENCED_TABLE_SCHEMA = DATABASE()
    AND REFERENCED_TABLE_NAME IN ('users', 'couple_relationships')
);
SET @hw_ddl = IF(@hw_drop_fks IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE `housework_mutations` ', @hw_drop_fks));
PREPARE hw_stmt FROM @hw_ddl;
EXECUTE hw_stmt;
DEALLOCATE PREPARE hw_stmt;

-- 回退后端版本可保留此结构。禁止恢复旧 open_flag：多个已结束周期将使旧 UNIQUE 失败。
-- 若需恢复外部 FK，先确认没有已删除主体的历史 ID；否则必须先恢复备份主体，不能删除共享历史。

