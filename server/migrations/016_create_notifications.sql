CREATE TABLE IF NOT EXISTS notifications (
  notification_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id         INT UNSIGNED NOT NULL,
  type            ENUM('todo_reminder', 'partner_nudge', 'system') NOT NULL DEFAULT 'system',
  title           VARCHAR(120) NOT NULL,
  body            VARCHAR(500) NOT NULL,
  related_id      INT UNSIGNED NULL,
  is_read         TINYINT(1) NOT NULL DEFAULT 0,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notif_user_read (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
