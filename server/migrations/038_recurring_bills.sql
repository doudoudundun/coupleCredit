-- 周期自动记账（F1）数据模型
--
-- 两张表：
--   * recurring_bill_templates：周期模板（weekly/monthly），口径对齐 bills：
--       - relationship_id NULL = 私人模板；否则挂在具体关系上（解绑后模板行保留但不可见）
--       - owner 1/2 = 关系内 user_id_1/user_id_2 位，3 = 共同；私人模板恒为 1
--       - 分类三列与 bills 相同（category_id + 名称/图标快照），category_id 为
--         CHAR(36) UUID —— item_categories.id 是 UUID（迁移 031），BIGINT 存不下
--       - amount DECIMAL(10,2) 元，与 bills.amount 一致
--   * recurring_bill_instances：物化出的待确认期次，UNIQUE(template_id, due_on)
--     是物化幂等键（并发/重跑不产生重复期次）。
--
-- 与 housework/goals 一致：身份列不建外键，解绑/删号不抹掉另一方历史。
-- 幂等：建表 IF NOT EXISTS，重复执行结果相同。
--
-- 回滚说明：默认禁止 DROP；确需清理时逆序 DROP：
--   recurring_bill_instances, recurring_bill_templates

-- 1) 周期模板
CREATE TABLE IF NOT EXISTS recurring_bill_templates (
    id                      BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    relationship_id         BIGINT UNSIGNED      NULL DEFAULT NULL COMMENT 'NULL=私人模板',
    user_id                 BIGINT UNSIGNED      NOT NULL COMMENT '创建者',
    owner                   TINYINT              NOT NULL COMMENT '1/2=关系位，3=共同；私人模板恒为 1',
    title                   VARCHAR(128)         NOT NULL,
    amount                  DECIMAL(10,2)        NOT NULL,
    income_type             VARCHAR(32)          NULL DEFAULT NULL COMMENT '0=支出 1=收入（与 bills.income_type 同语义，枚举值以字符串存储）',
    category_id             CHAR(36)             NULL DEFAULT NULL COMMENT '分类集合分类 UUID（item_categories.id）',
    category_name_snapshot  VARCHAR(20)          NULL DEFAULT NULL COMMENT '保存时分类名称快照（长度对齐 bills 列）',
    category_icon_snapshot  VARCHAR(64)          NULL DEFAULT NULL COMMENT '保存时分类图标快照（iconKey 或 emoji 值）',
    frequency               ENUM('weekly','monthly') NOT NULL,
    day_of_month            TINYINT              NULL DEFAULT NULL COMMENT 'monthly：1-31，短月截到月末',
    day_of_week             TINYINT              NULL DEFAULT NULL COMMENT 'weekly：0=周日 … 6=周六',
    next_due_on             DATE                 NOT NULL COMMENT '下一个待物化期次（物化后推进）',
    status                  ENUM('active','paused') NOT NULL DEFAULT 'active',
    created_at              TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rbt_user (user_id),
    KEY idx_rbt_relationship (relationship_id, status, next_due_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '周期记账模板：weekly/monthly，暂停不物化，删除不动已生成期次';

-- 2) 物化期次：UNIQUE(template_id, due_on) 为物化幂等键
CREATE TABLE IF NOT EXISTS recurring_bill_instances (
    id           BIGINT UNSIGNED                          NOT NULL AUTO_INCREMENT,
    template_id  BIGINT UNSIGNED                          NOT NULL,
    due_on       DATE                                     NOT NULL,
    status       ENUM('pending','confirmed','skipped')    NOT NULL DEFAULT 'pending',
    bill_id      BIGINT UNSIGNED                          NULL DEFAULT NULL COMMENT '确认后生成的正式账单 bills.bill_id',
    created_at   TIMESTAMP                                NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP                                NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rbi_template_due (template_id, due_on),
    KEY idx_rbi_status (status, due_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '周期记账期次：pending 待确认 / confirmed 已生成账单 / skipped 跳过';
