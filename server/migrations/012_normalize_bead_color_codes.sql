INSERT IGNORE INTO bead_colors (color_code, hex_color, color_group, is_transparent, created_at, updated_at)
SELECT CONCAT(LEFT(color_code, 1), LPAD(SUBSTRING(color_code, 2), 2, '0')),
       hex_color,
       color_group,
       is_transparent,
       created_at,
       updated_at
FROM bead_colors
WHERE color_code REGEXP '^[A-Z][0-9]$';

INSERT INTO bead_inventory (user_id, relationship_id, color_code, quantity, threshold_override, created_at, updated_at)
SELECT user_id,
       relationship_id,
       CONCAT(LEFT(color_code, 1), LPAD(SUBSTRING(color_code, 2), 2, '0')),
       quantity,
       threshold_override,
       created_at,
       updated_at
FROM bead_inventory
WHERE color_code REGEXP '^[A-Z][0-9]$'
ON DUPLICATE KEY UPDATE
  quantity = GREATEST(bead_inventory.quantity, VALUES(quantity)),
  threshold_override = COALESCE(bead_inventory.threshold_override, VALUES(threshold_override)),
  updated_at = GREATEST(bead_inventory.updated_at, VALUES(updated_at));

DELETE FROM bead_inventory
WHERE color_code REGEXP '^[A-Z][0-9]$';

INSERT INTO bead_blueprint_colors (blueprint_id, color_code, quantity, created_at, updated_at)
SELECT blueprint_id,
       CONCAT(LEFT(color_code, 1), LPAD(SUBSTRING(color_code, 2), 2, '0')),
       quantity,
       created_at,
       updated_at
FROM bead_blueprint_colors
WHERE color_code REGEXP '^[A-Z][0-9]$'
ON DUPLICATE KEY UPDATE
  quantity = bead_blueprint_colors.quantity + VALUES(quantity),
  updated_at = GREATEST(bead_blueprint_colors.updated_at, VALUES(updated_at));

DELETE FROM bead_blueprint_colors
WHERE color_code REGEXP '^[A-Z][0-9]$';

DELETE FROM bead_colors
WHERE color_code REGEXP '^[A-Z][0-9]$';
