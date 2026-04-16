-- WARNING: destructive rebuild script
-- This script drops and recreates the core schema.
-- Back up your database before running it.

CREATE DATABASE IF NOT EXISTS couple_credit_private CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE couple_credit_private;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS chat_messages;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS bills;
DROP TABLE IF EXISTS couple_relationships;
DROP TABLE IF EXISTS users;

CREATE TABLE IF NOT EXISTS users (
    id INT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    avatar VARCHAR(255) NULL,
    nickname VARCHAR(50) NULL,
    invite_code VARCHAR(64) NULL,
    status ENUM('active', 'inactive', 'banned') NOT NULL DEFAULT 'active',
    couple_status ENUM('single', 'pending', 'coupled') NOT NULL DEFAULT 'single',
    relationship_id INT UNSIGNED NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_invite_code (invite_code),
    KEY idx_users_status (status),
    KEY idx_users_couple_status (couple_status),
    KEY idx_users_relationship_id (relationship_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS couple_relationships (
    relationship_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id_1 INT NOT NULL,
    user_id_2 INT NOT NULL,
    status ENUM('active', 'dissolved') NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (relationship_id),
    UNIQUE KEY uk_relationship_pair (user_id_1, user_id_2),
    KEY idx_relationships_user_1 (user_id_1),
    KEY idx_relationships_user_2 (user_id_2),
    KEY idx_relationships_status (status),
    CONSTRAINT fk_relationships_user_1
        FOREIGN KEY (user_id_1) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_relationships_user_2
        FOREIGN KEY (user_id_2) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE users
ADD CONSTRAINT fk_users_relationship
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
    ON DELETE SET NULL
    ON UPDATE CASCADE;

CREATE TABLE IF NOT EXISTS bills (
    bill_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NULL,
    owner TINYINT NOT NULL,
    user_id INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    date DATE NOT NULL,
    time TIME NOT NULL,
    income_type TINYINT NOT NULL,
    is_help TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (bill_id),
    KEY idx_bills_relationship_id (relationship_id),
    KEY idx_bills_user_id (user_id),
    KEY idx_bills_date (date),
    KEY idx_bills_type (type),
    CONSTRAINT fk_bills_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE,
    CONSTRAINT fk_bills_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS inventory (
    inventory_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    relationship_id INT UNSIGNED NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    image_url VARCHAR(500) NULL,
    quantity DECIMAL(10,2) NOT NULL DEFAULT 0,
    unit VARCHAR(20) NOT NULL,
    threshold DECIMAL(10,2) NOT NULL DEFAULT 1,
    note VARCHAR(500) NULL,
    ai_image_prompt VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_consumed_at DATETIME NULL,
    PRIMARY KEY (inventory_id),
    KEY idx_inventory_user_id (user_id),
    KEY idx_inventory_relationship_id (relationship_id),
    KEY idx_inventory_category (category),
    KEY idx_inventory_updated_at (updated_at),
    KEY idx_inventory_low_stock (relationship_id, quantity, threshold),
    CONSTRAINT fk_inventory_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_inventory_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NULL,
    user_id INT NOT NULL,
    content TEXT NOT NULL,
    message_type ENUM('text', 'image', 'file', 'system') NOT NULL DEFAULT 'text',
    display_time VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    avatar_url VARCHAR(500) NULL,
    is_liked TINYINT(1) NOT NULL DEFAULT 0,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    bill_id INT UNSIGNED NULL,
    is_bill_candidate TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_chat_relationship_id (relationship_id),
    KEY idx_chat_user_id (user_id),
    KEY idx_chat_bill_id (bill_id),
    KEY idx_chat_created_at (created_at),
    CONSTRAINT fk_chat_relationship
        FOREIGN KEY (relationship_id) REFERENCES couple_relationships(relationship_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_chat_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_chat_bill
        FOREIGN KEY (bill_id) REFERENCES bills(bill_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
