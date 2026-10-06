-- Existing records stay NULL (private to their subject). New records capture
-- the active relationship so only that relationship can share them.
ALTER TABLE meal_records
    ADD COLUMN relationship_id INT UNSIGNED NULL AFTER user_id,
    ADD INDEX idx_meal_records_relationship_eaten (relationship_id, eaten_at),
    ADD CONSTRAINT fk_meal_records_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE SET NULL;

