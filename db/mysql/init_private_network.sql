CREATE DATABASE IF NOT EXISTS couple_credit_private CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'couple_app'@'%' IDENTIFIED BY 'ChangeThisPrivateDbPassword_2026!';
ALTER USER 'couple_app'@'%' IDENTIFIED BY 'ChangeThisPrivateDbPassword_2026!';

GRANT SELECT, INSERT, UPDATE, DELETE ON couple_credit_private.* TO 'couple_app'@'%';
FLUSH PRIVILEGES;

USE couple_credit_private;

CREATE TABLE IF NOT EXISTS users (
    id INT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    avatar VARCHAR(255) NULL,
    status ENUM('active','inactive','banned') DEFAULT 'active',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    invite_code VARCHAR(64) NULL,
    relationship_id INT UNSIGNED NULL,
    couple_status ENUM('single','pending','coupled') DEFAULT 'single',
    nickname VARCHAR(50) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_status (status),
    KEY idx_users_relationship_id (relationship_id)
);

CREATE TABLE IF NOT EXISTS bills (
    bill_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NULL,
    owner TINYINT(1) NOT NULL,
    user_id INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    date DATE NOT NULL,
    time TIME NOT NULL,
    income_type TINYINT(1) NOT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_help TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (bill_id),
    KEY idx_bills_relationship_id (relationship_id),
    KEY idx_bills_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS couple_relationships (
    relationship_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id_1 INT NOT NULL,
    user_id_2 INT NOT NULL,
    status ENUM('active','dissolved') NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (relationship_id),
    KEY idx_relationships_user_1 (user_id_1),
    KEY idx_relationships_user_2 (user_id_2)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    relationship_id INT UNSIGNED NOT NULL,
    user_id INT NOT NULL,
    content TEXT NOT NULL,
    message_type ENUM('text','image','file','system') DEFAULT 'text',
    display_time VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    avatar_url VARCHAR(500) NULL,
    is_liked TINYINT(1) DEFAULT 0,
    is_deleted TINYINT(1) DEFAULT 0,
    bill_id INT UNSIGNED NULL,
    is_bill_candidate TINYINT(1) DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_chat_relationship_id (relationship_id),
    KEY idx_chat_user_id (user_id),
    KEY idx_chat_bill_id (bill_id)
);
