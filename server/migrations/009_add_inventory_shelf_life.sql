ALTER TABLE inventory
    ADD COLUMN expiration_mode ENUM('date', 'calc') NULL AFTER ai_image_prompt,
    ADD COLUMN expiration_date DATE NULL AFTER expiration_mode,
    ADD COLUMN production_date DATE NULL AFTER expiration_date,
    ADD COLUMN shelf_life_days INT NULL AFTER production_date,
    ADD INDEX idx_inventory_expiration_date (expiration_date);
