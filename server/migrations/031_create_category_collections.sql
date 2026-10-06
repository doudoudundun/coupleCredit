-- 分类集合 + 预设导入（物资/账单）数据模型 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md（小程序仓库）第 8 节。
--
-- 核心不变量：
--   * 所有业务 ID 为 CHAR(36) UUID，API 中一律字符串；ID 永不回收复用，归档不删行。
--   * 集合防重：个人集合唯一键 (domain, direction_key, scope, owner_user_id)，
--     共享集合唯一键 (domain, direction_key, scope, relationship_id)。
--     MySQL 唯一键允许多个 NULL，故不参与的范围列保持 NULL（个人行 relationship_id 为 NULL、
--     共享行 owner_user_id 为 NULL，天然互相放行）。
--   * 物资 direction 语义为空，但 MySQL 唯一键把多个 NULL 视为互不冲突，
--     所以用生成列 direction_key = COALESCE(direction, '') 把 NULL 折叠成 '' 参与唯一键；
--     业务代码对 bills 写 'expense'/'income'，对 inventory 写 NULL。
--   * 同集合启用分类 name_key 不重复：生成列 active_name_key = IF(status='active', name_key, NULL)
--     加唯一索引（参考 030 的 open_flag 技巧）；归档行不参与去重。
--   * preset_bindings 是预设身份的唯一权威：(collection_id, entity_type, preset_key) 唯一，
--     删除记 tombstone，不靠实体表上的单个 source 字段。
--   * 本轮只加表加列，不回填、不改不删既有数据（回填在独立迁移）。
--
-- DDL 在 MySQL 隐式提交，不能靠 ROLLBACK 撤销。不要关闭 FOREIGN_KEY_CHECKS。
-- 幂等：建表全部 IF NOT EXISTS；加列/加索引用 INFORMATION_SCHEMA 检查后动态执行，重复执行结果相同。
--
-- 回滚说明：回滚仅下架后端版本/隐藏入口；本迁移创建的表默认保留。
--   若确需清理，按依赖逆序 DROP：
--     item_template_preferences, collection_mutations, collection_audits,
--     preset_bindings, item_templates, item_categories, category_collections
--   再 DROP COLUMN bills.category_id / bills.category_name_snapshot / bills.category_icon_snapshot
--   与 inventory.category_id（仅当确认无线上数据或已完整归档后方可执行，默认禁止）。

-- 1) 分类集合：个人(owner 唯一) / 共享(relationship 唯一)，direction_key 折叠 NULL。
CREATE TABLE IF NOT EXISTS category_collections (
    id               CHAR(36)                       NOT NULL,
    domain           ENUM('inventory','bills')      NOT NULL,
    direction        ENUM('expense','income')       NULL DEFAULT NULL COMMENT '账单收支方向；物资为 NULL',
    direction_key    VARCHAR(16) GENERATED ALWAYS AS (COALESCE(direction, '')) STORED,
    scope            ENUM('personal','couple')      NOT NULL,
    owner_user_id    INT                            NULL DEFAULT NULL COMMENT '个人集合 owner；共享为 NULL',
    relationship_id  INT UNSIGNED                   NULL DEFAULT NULL COMMENT '共享集合关系；个人为 NULL',
    version          INT UNSIGNED                   NOT NULL DEFAULT 1 COMMENT '集合版本：排序/归档/新增引用复核',
    status           ENUM('active','closed')        NOT NULL DEFAULT 'active',
    created_at       DATETIME(3)                    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)                    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_personal (domain, direction_key, scope, owner_user_id),
    UNIQUE KEY uk_cc_couple (domain, direction_key, scope, relationship_id),
    KEY idx_cc_owner (owner_user_id),
    KEY idx_cc_relationship (relationship_id),
    FOREIGN KEY (owner_user_id) REFERENCES users (id),
    FOREIGN KEY (relationship_id) REFERENCES couple_relationships (relationship_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '分类集合：物资/账单×收支×个人/共享，惰性创建';

-- 2) 分类：同集合启用 name_key 唯一（生成列部分唯一）；source_* 仅服务端按预设导入写入。
CREATE TABLE IF NOT EXISTS item_categories (
    id                  CHAR(36)                      NOT NULL,
    collection_id       CHAR(36)                      NOT NULL,
    name                VARCHAR(20)                   NOT NULL,
    name_key            VARCHAR(20)                   NOT NULL COMMENT 'NFC+英文小写+连续空白折叠为一个空格',
    active_name_key     VARCHAR(20) GENERATED ALWAYS AS (IF(status = 'active', name_key, NULL)) STORED,
    icon_type           ENUM('iconKey','emoji')       NULL DEFAULT NULL,
    icon_value          VARCHAR(64)                   NULL DEFAULT NULL,
    color               CHAR(7)                       NULL DEFAULT NULL,
    sort_order          INT                           NOT NULL DEFAULT 0,
    status              ENUM('active','archived')     NOT NULL DEFAULT 'active',
    version             INT UNSIGNED                  NOT NULL DEFAULT 1,
    source_pack_key     VARCHAR(64)                   NULL DEFAULT NULL,
    source_preset_key   VARCHAR(96)                   NULL DEFAULT NULL,
    source_pack_version INT UNSIGNED                  NULL DEFAULT NULL,
    created_by          INT                           NOT NULL,
    created_at          DATETIME(3)                   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)                   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ic_active_name (collection_id, active_name_key),
    KEY idx_ic_collection (collection_id, status, sort_order),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '物资/账单分类：归档保留ID，启用名唯一';

-- 3) 表单模板：只预填表单，不产生实际库存/账单；suggested_* 为 NULL 表示无建议值。
CREATE TABLE IF NOT EXISTS item_templates (
    id                   CHAR(36)                      NOT NULL,
    collection_id        CHAR(36)                      NOT NULL,
    category_id          CHAR(36)                      NOT NULL,
    name                 VARCHAR(40)                   NOT NULL,
    icon_type            ENUM('iconKey','emoji')       NULL DEFAULT NULL,
    icon_value           VARCHAR(64)                   NULL DEFAULT NULL,
    default_unit         VARCHAR(8)                    NULL DEFAULT NULL COMMENT '物资：建议单位',
    suggested_alert_line DECIMAL(10,2)                 NULL DEFAULT NULL COMMENT '物资：建议告警线，NULL=无建议',
    remark               VARCHAR(500)                  NULL DEFAULT NULL COMMENT '物资：备注',
    note                 VARCHAR(200)                  NULL DEFAULT NULL COMMENT '账单：说明',
    suggested_amount     DECIMAL(10,2)                 NULL DEFAULT NULL COMMENT '账单：建议金额，NULL=无建议',
    sort_order           INT                           NOT NULL DEFAULT 0,
    status               ENUM('active','archived')     NOT NULL DEFAULT 'active',
    version              INT UNSIGNED                  NOT NULL DEFAULT 1,
    source_pack_key      VARCHAR(64)                   NULL DEFAULT NULL,
    source_preset_key    VARCHAR(96)                   NULL DEFAULT NULL,
    source_pack_version  INT UNSIGNED                  NULL DEFAULT NULL,
    created_by           INT                           NOT NULL,
    created_at           DATETIME(3)                   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)                   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_it_collection (collection_id, status, sort_order),
    KEY idx_it_category (collection_id, category_id, status),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id),
    FOREIGN KEY (category_id) REFERENCES item_categories (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '物资/账单表单模板：归档不删行，不影响实际数据';

-- 4) 预设绑定：预设身份唯一权威；删除记 tombstone，恢复必须找回原 entity_id。
CREATE TABLE IF NOT EXISTS preset_bindings (
    id            BIGINT UNSIGNED                NOT NULL AUTO_INCREMENT,
    collection_id CHAR(36)                       NOT NULL,
    entity_type   ENUM('category','template')    NOT NULL,
    preset_key    VARCHAR(96)                    NOT NULL,
    entity_id     CHAR(36)                       NOT NULL,
    pack_key      VARCHAR(64)                    NOT NULL,
    pack_version  INT UNSIGNED                   NOT NULL,
    status        ENUM('active','tombstone')     NOT NULL DEFAULT 'active',
    created_at    DATETIME(3)                    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)                    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pb_preset (collection_id, entity_type, preset_key),
    KEY idx_pb_entity (entity_type, entity_id),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '预设绑定：导入/墓碑/恢复的唯一权威';

-- 5) 配置审计：与写操作同事务追加。
CREATE TABLE IF NOT EXISTS collection_audits (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    collection_id CHAR(36)        NOT NULL,
    actor_user_id INT             NOT NULL,
    action        VARCHAR(32)     NOT NULL COMMENT 'create/update/archive/restore/reorder/preset_apply/map/move_templates',
    entity_type   ENUM('category','template','collection','preference') NOT NULL,
    entity_id     VARCHAR(64)     NOT NULL,
    before_json   JSON            NULL DEFAULT NULL,
    after_json    JSON            NULL DEFAULT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ca_collection (collection_id, id),
    KEY idx_ca_entity (collection_id, entity_type, entity_id, id),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '分类集合配置审计';

-- 6) 幂等 receipt：唯一键 (collection, actor, mutationId)，requestHash 区分同ID异内容。
CREATE TABLE IF NOT EXISTS collection_mutations (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    collection_id      CHAR(36)        NOT NULL,
    actor_user_id      INT             NOT NULL,
    operation          VARCHAR(40)     NOT NULL,
    client_mutation_id VARCHAR(64)     NOT NULL,
    request_hash       CHAR(64)        NOT NULL,
    result_json        JSON            NOT NULL,
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cm_idem (collection_id, actor_user_id, operation, client_mutation_id),
    KEY idx_cm_collection (collection_id, created_at),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '幂等提交receipt：同ID同hash重放，异hash冲突，仅本人可读';

-- 7) 模板个人偏好：本人排序/置顶/隐藏，不影响共享顺序。
CREATE TABLE IF NOT EXISTS item_template_preferences (
    user_id                 INT          NOT NULL,
    collection_id           CHAR(36)     NOT NULL,
    ordered_template_ids    JSON         NOT NULL,
    pinned_template_ids     JSON         NOT NULL,
    hidden_template_ids     JSON         NOT NULL,
    version                 INT UNSIGNED NOT NULL DEFAULT 1,
    updated_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, collection_id),
    FOREIGN KEY (collection_id) REFERENCES category_collections (id),
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '模板个人偏好：常用面板顺序属个人，不改共享';

-- 8) bills 加分类引用与快照列（本轮不回填）；INFORMATION_SCHEMA 检查保证幂等。
SET @cc_bills_cat = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `bills` ADD COLUMN `category_id` CHAR(36) NULL DEFAULT NULL COMMENT ''分类集合分类ID，可空（legacy 未映射）''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bills' AND COLUMN_NAME = 'category_id');
PREPARE cc_stmt FROM @cc_bills_cat;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

SET @cc_bills_name = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `bills` ADD COLUMN `category_name_snapshot` VARCHAR(20) NULL DEFAULT NULL COMMENT ''保存时分类名称快照''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bills' AND COLUMN_NAME = 'category_name_snapshot');
PREPARE cc_stmt FROM @cc_bills_name;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

SET @cc_bills_icon = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `bills` ADD COLUMN `category_icon_snapshot` VARCHAR(64) NULL DEFAULT NULL COMMENT ''保存时分类图标快照（iconKey或emoji值）''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bills' AND COLUMN_NAME = 'category_icon_snapshot');
PREPARE cc_stmt FROM @cc_bills_icon;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

SET @cc_bills_idx = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `bills` ADD KEY `idx_bills_category_id` (`category_id`)',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bills' AND INDEX_NAME = 'idx_bills_category_id');
PREPARE cc_stmt FROM @cc_bills_idx;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

-- 9) inventory 加分类引用列（本轮不回填）。
SET @cc_inv_cat = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `inventory` ADD COLUMN `category_id` CHAR(36) NULL DEFAULT NULL COMMENT ''分类集合分类ID，可空（legacy 未映射）''',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND COLUMN_NAME = 'category_id');
PREPARE cc_stmt FROM @cc_inv_cat;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

SET @cc_inv_idx = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `inventory` ADD KEY `idx_inventory_category_id` (`category_id`)',
  'SELECT 1')
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND INDEX_NAME = 'idx_inventory_category_id');
PREPARE cc_stmt FROM @cc_inv_idx;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;
