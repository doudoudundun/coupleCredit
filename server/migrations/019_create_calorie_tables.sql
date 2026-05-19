-- Calorie management feature

CREATE TABLE IF NOT EXISTS ingredient_nutrition (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    calories_per_unit DECIMAL(8,2) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    category VARCHAR(50) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ingredient_nutrition_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS meal_records (
    record_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    meal_type ENUM('cook','eat_out','manual') NOT NULL,
    recipe_id INT UNSIGNED NULL,
    restaurant_id INT UNSIGNED NULL,
    title VARCHAR(100) NOT NULL,
    calories DECIMAL(8,2) NOT NULL,
    calorie_source ENUM('auto','manual') NOT NULL DEFAULT 'manual',
    note VARCHAR(500) NULL,
    eaten_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (recipe_id) REFERENCES recipes(recipe_id) ON DELETE SET NULL,
    FOREIGN KEY (restaurant_id) REFERENCES restaurants(restaurant_id) ON DELETE SET NULL,
    INDEX idx_meal_records_user_eaten (user_id, eaten_at),
    INDEX idx_meal_records_recipe (recipe_id),
    INDEX idx_meal_records_restaurant (restaurant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_calorie_goals (
    user_id INT NOT NULL PRIMARY KEY,
    daily_goal DECIMAL(8,2) NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE recipes
    ADD COLUMN total_calories DECIMAL(8,2) NULL AFTER steps,
    ADD COLUMN calorie_source ENUM('auto','manual') NULL AFTER total_calories;

ALTER TABLE restaurants
    ADD COLUMN default_calories DECIMAL(8,2) NULL AFTER avg_cost;
