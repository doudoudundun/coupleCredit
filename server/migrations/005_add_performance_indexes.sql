-- Performance indexes for couple_credit_private
-- Run: mysql -u root -proot couple_credit_private < 005_add_performance_indexes.sql

-- Bills: most common query filters by date range + user/relationship
ALTER TABLE bills ADD INDEX idx_bills_user_date (user_id, date);
ALTER TABLE bills ADD INDEX idx_bills_rel_date (relationship_id, date);

-- Couple relationships: lookup by user
ALTER TABLE couple_relationships ADD INDEX idx_couple_rel_user1 (user_id_1, status);
ALTER TABLE couple_relationships ADD INDEX idx_couple_rel_user2 (user_id_2, status);

-- Users: invite code lookup
ALTER TABLE users ADD INDEX idx_users_invite_code (invite_code, couple_status);

-- Chat messages: relationship + time pagination
ALTER TABLE chat_messages ADD INDEX idx_chat_rel_created (relationship_id, created_at);
ALTER TABLE chat_messages ADD INDEX idx_chat_rel_deleted (relationship_id, is_deleted, created_at);

-- Inventory: user lookup
ALTER TABLE inventory ADD INDEX idx_inventory_user (user_id);

-- Recipes: user + category
ALTER TABLE recipes ADD INDEX idx_recipes_user (user_id);
ALTER TABLE recipes ADD INDEX idx_recipes_category (category_id);
