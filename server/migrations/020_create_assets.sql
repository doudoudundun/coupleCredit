-- 创建资产清单表
CREATE TABLE IF NOT EXISTS assets (
    asset_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    image_url VARCHAR(500) NULL,
    original_image_url VARCHAR(500) NULL,
    purchase_date DATE NULL,
    purchase_price DECIMAL(12,2) NULL,
    current_value DECIMAL(12,2) NULL,
    status ENUM('active','idle','disposed') NOT NULL DEFAULT 'active',
    note VARCHAR(500) NULL,
    disposed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_relationship_id (relationship_id),
    INDEX idx_category (category),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
