CREATE TABLE IF NOT EXISTS period_records (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    start_date DATE NOT NULL,
    end_date DATE NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id) ON DELETE CASCADE,
    INDEX idx_period_user_start (user_id, start_date),
    INDEX idx_period_relationship (relationship_id, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
