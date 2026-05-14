CREATE TABLE IF NOT EXISTS ai_extraction_results (
  id INT AUTO_INCREMENT PRIMARY KEY,
  relationship_id INT UNSIGNED NOT NULL,
  trigger_message_id BIGINT NULL,
  result_type ENUM('bill','inventory','todo') NOT NULL,
  raw_data JSON NOT NULL,
  status ENUM('pending','confirmed','dismissed') DEFAULT 'pending',
  target_id INT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_rel_status (relationship_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
