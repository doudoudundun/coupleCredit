-- Additive migration; no existing data is rewritten or deleted.
-- Durable default in-app inbox; each event is also its consumer deduplication key.
-- External delivery is deliberately not enabled without platform opt-in.
CREATE TABLE IF NOT EXISTS diary_update_events (
 event_id CHAR(36) NOT NULL PRIMARY KEY,
 space_id CHAR(36) NOT NULL,
 recipient_id INT UNSIGNED NOT NULL,
 diary_id CHAR(36) NOT NULL,
 comment_id CHAR(36) NULL,
 event_type ENUM('published','reply') NOT NULL,
 resource_id CHAR(36) NOT NULL,
 state ENUM('unread','seen','revoked') NOT NULL DEFAULT 'unread',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_diary_update (recipient_id,event_type,resource_id),
 KEY idx_diary_update_inbox (recipient_id,space_id,state,created_at),
 KEY idx_diary_update_post (diary_id),
 KEY idx_diary_update_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
