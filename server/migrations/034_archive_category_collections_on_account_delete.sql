-- Preserve the surviving member's read-only access to shared category history
-- after account deletion. The archive ACL intentionally has no FK to users so
-- a later deletion of that member cannot block cleanup of their own account.
-- Existing shared collections remain relationship-scoped until an account is
-- deleted; the auth transaction then closes and detaches them atomically.

SET @cc_archived_user_col = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `category_collections`
     ADD COLUMN `archived_for_user_id` INT NULL DEFAULT NULL
     COMMENT ''Surviving member allowed read-only access after relationship deletion; intentionally no users FK'' AFTER `relationship_id`',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'category_collections' AND COLUMN_NAME = 'archived_for_user_id');
PREPARE cc_archive_stmt FROM @cc_archived_user_col;
EXECUTE cc_archive_stmt;
DEALLOCATE PREPARE cc_archive_stmt;

SET @cc_archived_user_idx = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `category_collections` ADD KEY `idx_cc_archived_user` (`archived_for_user_id`, `status`)',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'category_collections' AND INDEX_NAME = 'idx_cc_archived_user');
PREPARE cc_archive_stmt FROM @cc_archived_user_idx;
EXECUTE cc_archive_stmt;
DEALLOCATE PREPARE cc_archive_stmt;
