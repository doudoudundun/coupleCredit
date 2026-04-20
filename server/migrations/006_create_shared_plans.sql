CREATE TABLE IF NOT EXISTS shared_plans (
    plan_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    relationship_id INT UNSIGNED NULL,
    created_by INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    initial_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    current_balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    visibility ENUM('both', 'self') NOT NULL DEFAULT 'both',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_shared_plans_user (created_by),
    KEY idx_shared_plans_relationship (relationship_id),
    CONSTRAINT fk_shared_plans_user
        FOREIGN KEY (created_by) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_shared_plans_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE bills
    ADD COLUMN shared_plan_id INT UNSIGNED NULL AFTER relationship_id,
    ADD KEY idx_bills_shared_plan_id (shared_plan_id),
    ADD CONSTRAINT fk_bills_shared_plan
        FOREIGN KEY (shared_plan_id) REFERENCES shared_plans(plan_id)
        ON DELETE SET NULL ON UPDATE CASCADE;
