-- 账号密码保险箱：集中存储各平台的账号/密码/手机号/邮箱等信息
-- 敏感字段（password + securityQuestion + securityAnswer）合并做 AES-256-GCM 加密后存 encrypted_secret，
-- 其余字段明文以便列表展示/分类筛选；加密密钥仅服务端持有。
CREATE TABLE IF NOT EXISTS password_accounts (
    account_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    platform_name VARCHAR(100) NOT NULL,           -- 平台名（明文，用于展示/匹配图标）
    account_identifier VARCHAR(255) NOT NULL,      -- 账号（明文，登录名/用户名）
    phone VARCHAR(50) NULL,                        -- 注册手机号（明文）
    email VARCHAR(255) NULL,                       -- 注册邮箱（明文）
    website_url VARCHAR(500) NULL,                 -- 网站/APP 链接（明文）
    encrypted_secret TEXT NOT NULL,                -- AES-256-GCM 密文：JSON{password,securityQuestion,securityAnswer}
    category VARCHAR(50) NOT NULL DEFAULT '其他',  -- 分类（明文，用于筛选）
    note TEXT NULL,                                -- 备注（明文）
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_user_category (user_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
