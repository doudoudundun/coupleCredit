ALTER TABLE todo_items
  ADD COLUMN is_repeatable TINYINT(1) NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN series_id BIGINT NULL AFTER is_repeatable,
  ADD COLUMN completed_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER series_id;
