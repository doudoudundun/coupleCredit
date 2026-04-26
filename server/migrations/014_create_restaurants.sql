-- Restaurants table (Eat Out feature)
CREATE TABLE IF NOT EXISTS restaurants (
    restaurant_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NULL,
    image_url VARCHAR(500) NULL,
    route_image_url VARCHAR(500) NULL COMMENT 'Navigation route screenshot from home',
    avg_cost DECIMAL(10,2) NULL,
    distance DECIMAL(10,2) NULL COMMENT 'Distance from home in meters',
    address VARCHAR(300) NULL,
    note TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id) ON DELETE CASCADE,
    INDEX idx_user_restaurants (user_id),
    INDEX idx_rel_restaurants (relationship_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
