-- Monotonic per-user session version.  Incrementing it revokes every access
-- and refresh token for that user (password changes), while deleting the user
-- makes the middleware reject any remaining token because the row is gone.
--
-- Deploy this migration before the server release that reads the column.
ALTER TABLE users
  ADD COLUMN auth_token_version INT UNSIGNED NOT NULL DEFAULT 0 AFTER status;
