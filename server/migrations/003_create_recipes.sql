-- Recipes table
CREATE TABLE IF NOT EXISTS recipes (
    recipe_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NULL,
    image_url VARCHAR(500) NULL,
    steps TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Recipe ingredients table
CREATE TABLE IF NOT EXISTS recipe_ingredients (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    recipe_id INT UNSIGNED NOT NULL,
    inventory_id INT UNSIGNED NULL,
    ingredient_name VARCHAR(100) NOT NULL,
    quantity DECIMAL(10,2) DEFAULT 0,
    unit VARCHAR(20) NOT NULL DEFAULT '个',
    FOREIGN KEY (recipe_id) REFERENCES recipes(recipe_id) ON DELETE CASCADE,
    FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
