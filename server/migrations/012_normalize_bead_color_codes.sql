-- Normalize legacy one-digit bead codes (A1 -> A01) without changing the
-- existing merge semantics: inventory keeps the larger quantity and fills a
-- missing threshold, while blueprint quantities are added together.
--
-- Keep this migration on one connection.  The transaction makes a failed run
-- safe to roll back before retrying; the temporary snapshots also ensure that
-- source rows are not re-read after they have been merged.
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_012_bead_colors;
CREATE TEMPORARY TABLE tmp_012_bead_colors AS
SELECT
  bc.color_code AS old_color_code,
  CONCAT(LEFT(bc.color_code, 1), LPAD(SUBSTRING(bc.color_code, 2), 2, '0')) AS new_color_code,
  bc.hex_color,
  bc.color_group,
  bc.is_transparent,
  bc.created_at,
  bc.updated_at
FROM bead_colors AS bc
WHERE bc.color_code REGEXP '^[A-Z][0-9]$';

INSERT IGNORE INTO bead_colors
  (color_code, hex_color, color_group, is_transparent, created_at, updated_at)
SELECT
  source.new_color_code,
  source.hex_color,
  source.color_group,
  source.is_transparent,
  source.created_at,
  source.updated_at
FROM tmp_012_bead_colors AS source;

DROP TEMPORARY TABLE IF EXISTS tmp_012_bead_inventory;
CREATE TEMPORARY TABLE tmp_012_bead_inventory AS
SELECT
  bi.id AS source_id,
  bi.user_id,
  bi.relationship_id,
  CONCAT(LEFT(bi.color_code, 1), LPAD(SUBSTRING(bi.color_code, 2), 2, '0')) AS new_color_code,
  bi.quantity,
  bi.threshold_override,
  bi.created_at AS source_created_at,
  bi.updated_at AS source_updated_at
FROM bead_inventory AS bi
WHERE bi.color_code REGEXP '^[A-Z][0-9]$';

UPDATE bead_inventory AS target
INNER JOIN tmp_012_bead_inventory AS source
  ON target.user_id = source.user_id
 AND target.color_code = source.new_color_code
SET target.quantity = GREATEST(target.quantity, source.quantity),
    target.threshold_override = COALESCE(target.threshold_override, source.threshold_override),
    target.updated_at = GREATEST(target.updated_at, source.source_updated_at);

INSERT INTO bead_inventory
  (user_id, relationship_id, color_code, quantity, threshold_override, created_at, updated_at)
SELECT
  source.user_id,
  source.relationship_id,
  source.new_color_code,
  source.quantity,
  source.threshold_override,
  source.source_created_at,
  source.source_updated_at
FROM tmp_012_bead_inventory AS source
LEFT JOIN bead_inventory AS target
  ON target.user_id = source.user_id
 AND target.color_code = source.new_color_code
WHERE target.id IS NULL;

DELETE target
FROM bead_inventory AS target
INNER JOIN tmp_012_bead_inventory AS source ON source.source_id = target.id;
DROP TEMPORARY TABLE IF EXISTS tmp_012_bead_inventory;

DROP TEMPORARY TABLE IF EXISTS tmp_012_blueprint_colors;
CREATE TEMPORARY TABLE tmp_012_blueprint_colors AS
SELECT
  bbc.id AS source_id,
  bbc.blueprint_id,
  CONCAT(LEFT(bbc.color_code, 1), LPAD(SUBSTRING(bbc.color_code, 2), 2, '0')) AS new_color_code,
  bbc.quantity,
  bbc.created_at AS source_created_at,
  bbc.updated_at AS source_updated_at
FROM bead_blueprint_colors AS bbc
WHERE bbc.color_code REGEXP '^[A-Z][0-9]$';

UPDATE bead_blueprint_colors AS target
INNER JOIN tmp_012_blueprint_colors AS source
  ON target.blueprint_id = source.blueprint_id
 AND target.color_code = source.new_color_code
SET target.quantity = target.quantity + source.quantity,
    target.updated_at = GREATEST(target.updated_at, source.source_updated_at);

INSERT INTO bead_blueprint_colors
  (blueprint_id, color_code, quantity, created_at, updated_at)
SELECT
  source.blueprint_id,
  source.new_color_code,
  source.quantity,
  source.source_created_at,
  source.source_updated_at
FROM tmp_012_blueprint_colors AS source
LEFT JOIN bead_blueprint_colors AS target
  ON target.blueprint_id = source.blueprint_id
 AND target.color_code = source.new_color_code
WHERE target.id IS NULL;

DELETE target
FROM bead_blueprint_colors AS target
INNER JOIN tmp_012_blueprint_colors AS source ON source.source_id = target.id;
DROP TEMPORARY TABLE IF EXISTS tmp_012_blueprint_colors;

DELETE bc
FROM bead_colors AS bc
INNER JOIN tmp_012_bead_colors AS source ON source.old_color_code = bc.color_code;
DROP TEMPORARY TABLE IF EXISTS tmp_012_bead_colors;

COMMIT;
