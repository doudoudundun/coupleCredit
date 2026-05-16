CREATE TABLE IF NOT EXISTS push_tokens (
  token_id   INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    INT UNSIGNED NOT NULL,
  token      VARCHAR(500) NOT NULL,
  channel    ENUM('fcm', 'jpush') NOT NULL DEFAULT 'fcm',
  device_id  VARCHAR(100) NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_token_channel (token(191), channel),
  INDEX idx_push_user_channel (user_id, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Migrate existing fcm_tokens data
INSERT IGNORE INTO push_tokens (user_id, token, channel, device_id, created_at, updated_at)
SELECT user_id, token, 'fcm', device_id, created_at, updated_at FROM fcm_tokens;
