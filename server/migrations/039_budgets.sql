-- 预算（F2）数据模型
--
-- 一行 = 某用户在某范围（私人 relationship_id NULL / 某关系）对某分类（NULL=月度总预算）
-- 设定的月度预算金额（DECIMAL(10,2) 元）。
--   * 范围口径与 bills 一致：私人 + active 关系；解绑后关系行保留但不可见。
--   * category_id 为 CHAR(36) UUID —— item_categories.id 是 UUID（迁移 031），
--     BIGINT 存不下；NULL 表示月度总预算。
--   * MySQL 唯一键把多个 NULL 视为互不冲突，UNIQUE(user_id, relationship_id, category_id)
--     只能约束分类行；总预算行（category_id NULL）由写入路径（事务内先删后插）保证唯一，
--     读路径按最新 updated_at 合并兜底。
--
-- 幂等：建表 IF NOT EXISTS，重复执行结果相同。
-- 回滚说明：默认禁止 DROP；确需清理时 DROP budgets。

CREATE TABLE IF NOT EXISTS budgets (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    relationship_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT 'NULL=私人预算；否则关系内预算',
    user_id         BIGINT UNSIGNED NOT NULL COMMENT '设定者',
    category_id     CHAR(36)        NULL DEFAULT NULL COMMENT 'NULL=月度总预算；分类集合分类 UUID',
    amount          DECIMAL(10,2)   NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_budgets_scope (user_id, relationship_id, category_id),
    KEY idx_budgets_relationship (relationship_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '月度预算：私人/关系 × 总预算/分类预算，amount 元';
