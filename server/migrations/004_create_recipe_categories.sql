-- Recipe categories table
CREATE TABLE IF NOT EXISTS recipe_categories (
    category_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    relationship_id INT UNSIGNED NOT NULL,
    name VARCHAR(50) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name_rel (relationship_id, name),
    KEY idx_category_sort (relationship_id, sort_order),
    CONSTRAINT fk_category_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Add category_id to recipes table
ALTER TABLE recipes
    ADD COLUMN category_id INT UNSIGNED NULL AFTER relationship_id,
    ADD KEY idx_recipes_category_id (category_id),
    ADD CONSTRAINT fk_recipes_category
        FOREIGN KEY (category_id) REFERENCES recipe_categories(category_id)
        ON DELETE SET NULL ON UPDATE CASCADE;
