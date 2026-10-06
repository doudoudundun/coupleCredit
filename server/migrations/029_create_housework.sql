-- 家务记录（housework）V1 数据模型
--
-- 背景见 docs/HOUSEWORK_RECORDS_SPEC_20261001.md（小程序仓库）。
-- 核心不变量：
--   * 每次解绑/重绑都生成新的不可复用 cycle；relationship_id 仍按既有语义全局复用，
--     但家务隔离边界是 cycle_id / space_id，绝不按「当前用户 + partnerId」拼范围。
--   * 共享空间冻结（closed）后原成员只读，行不删除；周期、成员、参与者与审计身份
--     为历史快照 ID，不对 users/couple_relationships 建外键，帐号删除不能抹掉另一成员的历史。
--   * 所有业务 ID 为 CHAR(36) UUID，API 中一律字符串；数量/权重 DECIMAL(10,2)，
--     API 中以两位十进制字符串传输；shareBps/version/revision 用整数。
--
-- 部署顺序：
--   1. 先执行本迁移（纯新增表，不改写既有账单/物资数据）；
--   2. 再上后端版本（couple.js 生命周期接入 + /api/housework 路由）；
--   3. 最后开小程序入口。
--   无迁移运行器，由 DBA 人工按序号顺序执行。
--
-- 回滚说明：
--   回滚仅下架后端版本/隐藏入口；本迁移创建的 housework_* 表先保留，
--   不回滚时 DROP 任何用户数据表。若确需清理，按依赖逆序 DROP：
--     housework_mutations, housework_config_revisions, housework_record_revisions,
--     housework_preferences, housework_record_participants, housework_records,
--     housework_templates, housework_categories, housework_space_members,
--     housework_spaces, housework_relationship_cycles
--   （仅当确认无线上数据或已完整归档后方可执行，默认禁止。）

-- 1) 家务关系周期：每次绑定新建、永不复用/重开；每个 relationshipId 最多一个未结束 cycle。
--    open_flag 为生成列：1 表示未结束，结束后为 NULL（唯一键允许多个 NULL）。
CREATE TABLE IF NOT EXISTS housework_relationship_cycles (
    cycle_id          CHAR(36)     NOT NULL,
    relationship_id   INT UNSIGNED NOT NULL,
    member_user_id_1  INT          NOT NULL,
    member_user_id_2  INT          NOT NULL,
    started_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at          DATETIME(3)  NULL DEFAULT NULL,
    open_flag         TINYINT      GENERATED ALWAYS AS (IF(ended_at IS NULL, 1, NULL)) STORED,
    PRIMARY KEY (cycle_id),
    UNIQUE KEY uk_hw_cycles_relationship_open (relationship_id, open_flag),
    KEY idx_hw_cycles_relationship (relationship_id, ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '家务关系周期：每次绑定新建，解绑关闭，永不复用';

-- 2) 空间：个人空间唯一 owner；共享空间唯一 cycle（多行 NULL 互不冲突）。
CREATE TABLE IF NOT EXISTS housework_spaces (
    space_id      CHAR(36)                                                         NOT NULL,
    scope         ENUM('personal','couple')                                        NOT NULL,
    owner_user_id INT                                                              NULL DEFAULT NULL,
    cycle_id      CHAR(36)                                                         NULL DEFAULT NULL,
    status        ENUM('active','closed')                                          NOT NULL DEFAULT 'active',
    timezone      VARCHAR(64)                                                      NOT NULL DEFAULT 'Asia/Shanghai',
    revision      BIGINT UNSIGNED                                                  NOT NULL DEFAULT 0,
    version       INT UNSIGNED                                                     NOT NULL DEFAULT 1,
    settings_json JSON                                                             NOT NULL,
    created_at    DATETIME(3)                                                      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at     DATETIME(3)                                                      NULL DEFAULT NULL,
    PRIMARY KEY (space_id),
    UNIQUE KEY uk_hw_spaces_owner (owner_user_id),
    UNIQUE KEY uk_hw_spaces_cycle (cycle_id),
    KEY idx_hw_spaces_scope_status (scope, status),
    FOREIGN KEY (owner_user_id) REFERENCES users (id),
    FOREIGN KEY (cycle_id) REFERENCES housework_relationship_cycles (cycle_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '家务空间：个人(owner唯一) / 共享(cycle唯一)，解绑冻结不删行';

-- 3) 空间成员：闭合后保留原成员只读；display_name_snapshot 为加入时快照。
CREATE TABLE IF NOT EXISTS housework_space_members (
    space_id              CHAR(36)    NOT NULL,
    user_id               INT         NOT NULL,
    display_name_snapshot VARCHAR(80) NOT NULL,
    joined_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at              DATETIME(3) NULL DEFAULT NULL,
    PRIMARY KEY (space_id, user_id),
    KEY idx_hw_members_user (user_id, space_id),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '空间成员：含显示名快照，冻结后保留';

-- 4) 分类：兜底分类每空间唯一（fallback_key 生成列实现部分唯一）；
--    非空 presetCategoryKey 空间内唯一（含归档项）。
CREATE TABLE IF NOT EXISTS housework_categories (
    category_id        CHAR(36)                          NOT NULL,
    space_id           CHAR(36)                          NOT NULL,
    name               VARCHAR(20)                       NOT NULL,
    normalized_name    VARCHAR(20)                       NOT NULL,
    icon_json          JSON                              NULL DEFAULT NULL,
    color              CHAR(7)                           NULL DEFAULT NULL,
    sort_order         INT                               NOT NULL DEFAULT 0,
    is_fallback        TINYINT(1)                        NOT NULL DEFAULT 0,
    status             ENUM('active','archived')         NOT NULL DEFAULT 'active',
    preset_category_key VARCHAR(64)                      NULL DEFAULT NULL,
    version            INT UNSIGNED                      NOT NULL DEFAULT 1,
    fallback_key       TINYINT GENERATED ALWAYS AS (IF(is_fallback = 1, 1, NULL)) STORED,
    created_at         DATETIME(3)                       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)                       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (category_id),
    UNIQUE KEY uk_hw_categories_fallback (space_id, fallback_key),
    UNIQUE KEY uk_hw_categories_preset (space_id, preset_category_key),
    KEY idx_hw_categories_space (space_id, status, sort_order),
    KEY idx_hw_categories_name (space_id, normalized_name, status),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '家务分类：兜底唯一不可归档，推荐key唯一';

-- 5) 模板：非空 presetKey 空间内永久唯一（含归档）；同空间同分类未归档名称去重由服务端校验。
CREATE TABLE IF NOT EXISTS housework_templates (
    template_id            CHAR(36)                    NOT NULL,
    space_id               CHAR(36)                    NOT NULL,
    category_id            CHAR(36)                    NOT NULL,
    name                   VARCHAR(40)                 NOT NULL,
    normalized_name        VARCHAR(40)                 NOT NULL,
    description            VARCHAR(300)                NULL DEFAULT NULL,
    icon_json              JSON                        NULL DEFAULT NULL,
    color                  CHAR(7)                     NULL DEFAULT NULL,
    sort_order             INT                         NOT NULL DEFAULT 0,
    measure_mode           ENUM('event','quantity')    NOT NULL,
    unit                   VARCHAR(8)                  NOT NULL DEFAULT '次',
    default_quantity       DECIMAL(10,2)               NULL DEFAULT NULL,
    duration_enabled       TINYINT(1)                  NOT NULL DEFAULT 0,
    default_duration_minutes INT                       NULL DEFAULT NULL,
    weight                 DECIMAL(10,2)               NOT NULL DEFAULT 1.00,
    fields_json            JSON                        NOT NULL,
    status                 ENUM('active','archived')   NOT NULL DEFAULT 'active',
    preset_key             VARCHAR(64)                 NULL DEFAULT NULL,
    version                INT UNSIGNED                NOT NULL DEFAULT 1,
    created_at             DATETIME(3)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_hw_templates_preset (space_id, preset_key),
    KEY idx_hw_templates_space (space_id, status, sort_order),
    KEY idx_hw_templates_category (space_id, category_id, normalized_name, status),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES housework_categories (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '家务模板：计量/字段/权重定义，归档不删行';

-- 6) 记录：含名称/分类/计量/权重快照；completedTime 存可空 HH:mm 字符串，不做午夜补全。
CREATE TABLE IF NOT EXISTS housework_records (
    record_id           CHAR(36)             NOT NULL,
    space_id            CHAR(36)             NOT NULL,
    template_id         CHAR(36)             NULL DEFAULT NULL,
    template_version    INT UNSIGNED         NULL DEFAULT NULL,
    name                VARCHAR(40)          NOT NULL COMMENT '冗余名称列，用于搜索/临时分组，快照仍为准',
    category_id_snapshot CHAR(36)            NOT NULL,
    snapshot_json       JSON                 NOT NULL,
    completed_date      DATE                 NOT NULL,
    completed_time      VARCHAR(5)           NULL DEFAULT NULL COMMENT 'HH:mm，可空，不补午夜',
    quantity            DECIMAL(10,2)        NOT NULL,
    duration_minutes    INT                  NULL DEFAULT NULL,
    weight_snapshot     DECIMAL(10,2)        NOT NULL,
    field_values_json   JSON                 NOT NULL,
    note                VARCHAR(500)         NULL DEFAULT NULL,
    created_by          INT                  NOT NULL,
    updated_by          INT                  NOT NULL,
    client_mutation_id  VARCHAR(64)          NOT NULL,
    version             INT UNSIGNED         NOT NULL DEFAULT 1,
    created_at          DATETIME(3)          NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)          NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at          DATETIME(3)          NULL DEFAULT NULL,
    deleted_by          INT                  NULL DEFAULT NULL,
    PRIMARY KEY (record_id),
    KEY idx_hw_records_space (space_id, deleted_at, completed_date, record_id),
    KEY idx_hw_records_template (space_id, template_id),
    KEY idx_hw_records_deleted (space_id, deleted_at),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE,
    FOREIGN KEY (template_id) REFERENCES housework_templates (template_id),
    FOREIGN KEY (category_id_snapshot) REFERENCES housework_categories (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '家务完成记录：软删除，快照为准';

-- 7) 记录参与者：UNIQUE(record_id,user_id)，人数恰为1或2；shareBps 整数基点。
CREATE TABLE IF NOT EXISTS housework_record_participants (
    record_id             CHAR(36)    NOT NULL,
    user_id               INT         NOT NULL,
    share_bps             INT UNSIGNED NOT NULL COMMENT '份额基点，10000=100%',
    display_name_snapshot VARCHAR(80) NOT NULL,
    PRIMARY KEY (record_id, user_id),
    KEY idx_hw_participants_user (user_id, record_id),
    FOREIGN KEY (record_id) REFERENCES housework_records (record_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '记录参与者及当时显示名快照';

-- 8) 个人偏好：spaceId+userId 唯一，只由本人写，不进配置审计。
CREATE TABLE IF NOT EXISTS housework_preferences (
    space_id                 CHAR(36)                 NOT NULL,
    user_id                  INT                      NOT NULL,
    pinned_template_ids_json JSON                     NOT NULL,
    hidden_template_ids_json JSON                     NOT NULL,
    ordered_template_ids_json JSON                    NOT NULL,
    layout                   ENUM('grid','list')      NOT NULL DEFAULT 'grid',
    display_aliases_json     JSON                     NOT NULL,
    default_performer_mode   ENUM('self','choose')    NOT NULL DEFAULT 'self',
    version                  INT UNSIGNED             NOT NULL DEFAULT 1,
    created_at               DATETIME(3)              NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)              NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (space_id, user_id),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '个人快捷偏好：置顶/隐藏/排序/布局/称呼/默认执行者';

-- 9) 记录修订日志：与记录写操作同事务追加。
CREATE TABLE IF NOT EXISTS housework_record_revisions (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    record_id         CHAR(36)        NOT NULL,
    space_id          CHAR(36)        NOT NULL,
    actor_user_id     INT             NOT NULL,
    action            ENUM('create','update','delete','restore') NOT NULL,
    before_version    INT UNSIGNED    NULL DEFAULT NULL,
    after_version     INT UNSIGNED    NOT NULL,
    changed_fields_json JSON          NULL DEFAULT NULL COMMENT '变更摘要字段名数组',
    before_json       JSON            NULL DEFAULT NULL,
    after_json        JSON            NULL DEFAULT NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_hw_record_revisions (record_id, id),
    KEY idx_hw_record_revisions_space (space_id, id),
    FOREIGN KEY (record_id) REFERENCES housework_records (record_id) ON DELETE CASCADE,
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '记录审计：操作人/动作/前后版本/快照';

-- 10) 配置修订日志：分类/模板/设置的共享配置变更（个人偏好不入此表）。
CREATE TABLE IF NOT EXISTS housework_config_revisions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id       CHAR(36)        NOT NULL,
    entity_type    ENUM('category','template','settings') NOT NULL,
    entity_id      VARCHAR(64)     NOT NULL,
    actor_user_id  INT             NOT NULL,
    action         VARCHAR(32)     NOT NULL COMMENT 'create/update/archive/restore/move_templates',
    before_version INT UNSIGNED    NULL DEFAULT NULL,
    after_version  INT UNSIGNED    NOT NULL,
    before_json    JSON            NULL DEFAULT NULL,
    after_json     JSON            NULL DEFAULT NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_hw_config_revisions (space_id, entity_type, entity_id, id),
    KEY idx_hw_config_revisions_space (space_id, id),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '配置审计：分类/模板/设置变更';

-- 11) 幂等 receipt：唯一键 (space, actor, operation, mutationId)，requestHash 区分同ID异内容。
CREATE TABLE IF NOT EXISTS housework_mutations (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id           CHAR(36)        NOT NULL,
    actor_user_id      INT             NOT NULL,
    operation          VARCHAR(40)     NOT NULL,
    client_mutation_id VARCHAR(64)     NOT NULL,
    request_hash       CHAR(64)        NOT NULL,
    result_id          VARCHAR(64)     NULL DEFAULT NULL COMMENT '主结果实体ID，如 recordId',
    result_json        JSON            NOT NULL,
    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_hw_mutations (space_id, actor_user_id, operation, client_mutation_id),
    KEY idx_hw_mutations_space (space_id, created_at),
    FOREIGN KEY (space_id) REFERENCES housework_spaces (space_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '幂等提交receipt：同ID同hash重放，异hash冲突';
