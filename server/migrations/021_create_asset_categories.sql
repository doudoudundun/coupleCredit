-- 创建资产分类表
CREATE TABLE IF NOT EXISTS asset_categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NULL,
    relationship_id INT UNSIGNED NULL,
    name VARCHAR(50) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category (user_id, relationship_id, name),
    INDEX idx_relationship (relationship_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 插入默认分类
INSERT INTO asset_categories (name, is_default) VALUES
('电子设备', TRUE),
('衣物', TRUE),
('包包', TRUE),
('鞋履', TRUE),
('家具', TRUE),
('配饰', TRUE),
('美妆', TRUE),
('书籍', TRUE),
('其他', TRUE);
