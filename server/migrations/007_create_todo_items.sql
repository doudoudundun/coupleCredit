CREATE TABLE IF NOT EXISTS todo_items (
    todo_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NULL,
    priority ENUM('low', 'medium', 'high') NOT NULL DEFAULT 'medium',
    fuzzy_date_text VARCHAR(80) NULL,
    image_url VARCHAR(255) NULL,
    status ENUM('open', 'done', 'missed') NOT NULL DEFAULT 'open',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_todo_user (user_id),
    KEY idx_todo_relationship (relationship_id),
    KEY idx_todo_status_priority (status, priority, updated_at),
    CONSTRAINT fk_todo_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_todo_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
