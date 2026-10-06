-- 共同目标 & 共同小记（v0.2）数据模型
--
-- 依据《coupleCredit_共同生活产品规格 v0.2》与小程序仓库 docs/API_CONTRACT_GOALS_DIARY_20261006.md。
-- 核心不变量：
--   * 「空间」复用 housework 生命周期：个人空间（scope=personal, owner 唯一）与
--     共享空间（scope=couple, 每 bind cycle 唯一）。解绑关闭 cycle + 冻结空间时，
--     目标冻结、小记停止互看（行保留，作者本人仍可读 = 本人私有归档）。
--   * 金额一律为「分」的整数；current_amount_fen 只允许服务端在流水事务内改写。
--   * 每空间最多一个 active 目标：active_flag 生成列 + 唯一键在数据库层兜底。
--   * 邀请令牌只存 SHA-256 哈希；同一发起人同一目标仅一个 active 邀请（active_flag 唯一键）。
--   * 审计/快照身份不对 users/couple_relationships 建外键（与 housework 记录口径一致），
--     帐号删除不能抹掉另一成员的历史。
--
-- 部署顺序：先执行本迁移（纯新增表），再上后端版本，最后开小程序入口。
-- 回滚说明：默认禁止 DROP；确需清理时按依赖逆序：
--   idempotency_records, space_diary_theme, diary_comments, diary_media, diary_posts,
--   goal_invitations, couple_goal_events, couple_goal_entries, couple_goals

-- 1) 共同目标
CREATE TABLE IF NOT EXISTS couple_goals (
    goal_id             CHAR(36)                                                  NOT NULL,
    space_id            CHAR(36)                                                  NOT NULL COMMENT '个人空间或共享空间（housework_spaces.space_id 快照语义，无 FK）',
    relationship_id     INT UNSIGNED                                              NULL DEFAULT NULL COMMENT '共享空间目标的关系快照；个人空间目标为 NULL',
    created_by          INT                                                       NOT NULL,
    title               VARCHAR(30)                                               NOT NULL,
    description         VARCHAR(200)                                              NULL DEFAULT NULL,
    target_amount_fen   BIGINT UNSIGNED                                           NOT NULL,
    current_amount_fen  BIGINT                                                    NOT NULL DEFAULT 0 COMMENT '只读净额，仅流水事务可改写',
    currency            CHAR(3)                                                   NOT NULL DEFAULT 'CNY',
    target_date         DATE                                                      NULL DEFAULT NULL,
    timezone            VARCHAR(64)                                               NOT NULL DEFAULT 'Asia/Shanghai',
    status              ENUM('active','completed','archived','frozen')            NOT NULL DEFAULT 'active',
    version             INT UNSIGNED                                              NOT NULL DEFAULT 1,
    legacy_plan_id      BIGINT                                                    NULL DEFAULT NULL COMMENT '旧 shared_plans 迁移来源（可选）',
    active_flag         TINYINT GENERATED ALWAYS AS (IF(status='active',1,NULL)) STORED,
    created_at          DATETIME(3)                                               NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)                                               NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    completed_at        DATETIME(3)                                               NULL DEFAULT NULL,
    PRIMARY KEY (goal_id),
    UNIQUE KEY uk_goals_space_active (space_id, active_flag),
    KEY idx_goals_space_status (space_id, status),
    KEY idx_goals_creator (created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '共同目标：每空间最多一个进行中目标';

-- 2) 目标流水（冲正只标记，不写反向流水）
CREATE TABLE IF NOT EXISTS couple_goal_entries (
    entry_id            CHAR(36)                                  NOT NULL,
    goal_id             CHAR(36)                                  NOT NULL,
    actor_id            INT                                       NOT NULL,
    type                ENUM('increase','decrease')               NOT NULL,
    amount_fen          BIGINT UNSIGNED                           NOT NULL,
    note                VARCHAR(200)                              NULL DEFAULT NULL,
    occurred_at         DATETIME(3)                               NOT NULL,
    status              ENUM('valid','reversed')                  NOT NULL DEFAULT 'valid',
    reversal_event_id   BIGINT UNSIGNED                           NULL DEFAULT NULL,
    created_at          DATETIME(3)                               NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (entry_id),
    KEY idx_goal_entries_goal (goal_id, status, occurred_at),
    KEY idx_goal_entries_actor (actor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '目标流水：净额=valid 的 increase-decrease';

-- 3) 目标活动/审计事件（含设置变更、冲正、状态机、冻结、空间转移）
CREATE TABLE IF NOT EXISTS couple_goal_events (
    event_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id    CHAR(36)        NOT NULL,
    space_id   CHAR(36)        NOT NULL,
    actor_id   INT             NULL DEFAULT NULL,
    type       VARCHAR(32)     NOT NULL COMMENT 'create|update|entry|reverse|complete|archive|reopen|freeze|transfer',
    ref_id     CHAR(36)        NULL DEFAULT NULL,
    meta_json  JSON            NULL DEFAULT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_goal_events_space (space_id, event_id),
    KEY idx_goal_events_goal (goal_id, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '目标活动流与审计';

-- 4) 情境邀请：短期、可撤销、只存令牌哈希
CREATE TABLE IF NOT EXISTS goal_invitations (
    invitation_id CHAR(36)                               NOT NULL,
    goal_id       CHAR(36)                               NOT NULL,
    inviter_id    INT                                    NOT NULL,
    token_hash    CHAR(64)                               NOT NULL COMMENT 'SHA-256(令牌) 十六进制',
    status        ENUM('active','revoked','used','expired') NOT NULL DEFAULT 'active',
    expires_at    DATETIME(3)                            NOT NULL,
    used_by       INT                                    NULL DEFAULT NULL,
    used_at       DATETIME(3)                            NULL DEFAULT NULL,
    created_at    DATETIME(3)                            NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    active_flag   TINYINT GENERATED ALWAYS AS (IF(status='active',1,NULL)) STORED,
    PRIMARY KEY (invitation_id),
    UNIQUE KEY uk_invitations_token (token_hash),
    UNIQUE KEY uk_invitations_active (inviter_id, goal_id, active_flag),
    KEY idx_invitations_goal (goal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '情境邀请：72h 有效，重新生成即撤销旧令牌';

-- 5) 共同小记
CREATE TABLE IF NOT EXISTS diary_posts (
    diary_id        CHAR(36)                     NOT NULL,
    space_id        CHAR(36)                     NOT NULL COMMENT '共享空间；冻结后仅作者本人可读（私有归档）',
    relationship_id INT UNSIGNED                 NOT NULL COMMENT '发布时关系快照，恢复校验用',
    author_id       INT                          NOT NULL,
    body            MEDIUMTEXT                   NOT NULL COMMENT '应用层限制 5000 Unicode 字符',
    occurred_on     DATE                         NOT NULL,
    mood_code       VARCHAR(16)                  NULL DEFAULT NULL,
    mood_text       VARCHAR(20)                  NULL DEFAULT NULL,
    status          ENUM('visible','deleted')    NOT NULL DEFAULT 'visible',
    edited          TINYINT(1)                   NOT NULL DEFAULT 0,
    comment_count   INT UNSIGNED                 NOT NULL DEFAULT 0 COMMENT '可见未删除评论权威计数',
    version         INT UNSIGNED                 NOT NULL DEFAULT 1,
    created_at      DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      DATETIME(3)                  NULL DEFAULT NULL,
    PRIMARY KEY (diary_id),
    KEY idx_diary_space (space_id, status, occurred_on, created_at),
    KEY idx_diary_author (author_id, status, deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '共同小记：删除为软删，回收期 30 天仅作者可恢复';

-- 6) 小记媒体：转码闸门 + 授权版本（解绑/删除即失效）
CREATE TABLE IF NOT EXISTS diary_media (
    media_id     CHAR(36)                                    NOT NULL,
    owner_id     INT                                         NOT NULL COMMENT '上传者，归档投影只含本人图片',
    purpose      ENUM('diary','background')                  NOT NULL,
    status       ENUM('pending','transcoded','ready','failed') NOT NULL DEFAULT 'pending',
    mime         VARCHAR(32)                                 NOT NULL,
    size_bytes   INT UNSIGNED                                NOT NULL DEFAULT 0,
    width        INT UNSIGNED                                NULL DEFAULT NULL,
    height       INT UNSIGNED                                NULL DEFAULT NULL,
    storage_path VARCHAR(255)                                NULL DEFAULT NULL COMMENT '完整变体相对路径（uploads/ 下）',
    thumb_path   VARCHAR(255)                                NULL DEFAULT NULL,
    diary_id     CHAR(36)                                    NULL DEFAULT NULL,
    space_id     CHAR(36)                                    NULL DEFAULT NULL,
    sort_order   TINYINT UNSIGNED                            NOT NULL DEFAULT 0,
    auth_version INT UNSIGNED                                NOT NULL DEFAULT 1 COMMENT '授权版本：解绑/删除递增，旧签名 URL 立即失效',
    created_at   DATETIME(3)                                 NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    bound_at     DATETIME(3)                                 NULL DEFAULT NULL,
    PRIMARY KEY (media_id),
    KEY idx_diary_media_diary (diary_id, sort_order),
    KEY idx_diary_media_owner (owner_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '小记媒体：complete 前不可绑定；未绑定上传 24h 清理';

-- 7) 小记评论（两层：主评论 + 挂根的平铺回复）
CREATE TABLE IF NOT EXISTS diary_comments (
    comment_id          CHAR(36)                  NOT NULL,
    diary_id            CHAR(36)                  NOT NULL,
    space_id            CHAR(36)                  NOT NULL,
    author_id           INT                       NOT NULL,
    body                VARCHAR(500)              NOT NULL,
    root_comment_id     CHAR(36)                  NULL DEFAULT NULL COMMENT 'NULL=主评论；回复始终挂根',
    reply_to_comment_id CHAR(36)                  NULL DEFAULT NULL,
    reply_to_user_id    INT                       NULL DEFAULT NULL,
    status              ENUM('visible','deleted') NOT NULL DEFAULT 'visible',
    version             INT UNSIGNED              NOT NULL DEFAULT 1,
    created_at          DATETIME(3)               NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)               NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (comment_id),
    KEY idx_diary_comments_diary (diary_id, root_comment_id, status, created_at),
    KEY idx_diary_comments_root (root_comment_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '小记评论：仅两层；删主评论有回复时留无正文占位';

-- 8) 空间小记背景（模板或自定义媒体；版本锁防并发）
CREATE TABLE IF NOT EXISTS space_diary_theme (
    space_id        CHAR(36)                     NOT NULL,
    kind            ENUM('none','template','media') NOT NULL DEFAULT 'none',
    template_id     VARCHAR(32)                  NULL DEFAULT NULL COMMENT '内置模板 sunset|breeze|mist',
    media_id        CHAR(36)                     NULL DEFAULT NULL,
    crop_json       JSON                         NULL DEFAULT NULL,
    focus_point_json JSON                        NULL DEFAULT NULL,
    overlay         TINYINT UNSIGNED             NOT NULL DEFAULT 45 COMMENT '柔化百分比 20-70',
    version         INT UNSIGNED                 NOT NULL DEFAULT 1,
    updated_by      INT                          NULL DEFAULT NULL,
    updated_at      DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (space_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '空间小记背景：任一成员可改，乐观锁';

-- 9) 幂等键（写操作通用；按 操作人+作用域+业务键 去重）
CREATE TABLE IF NOT EXISTS idempotency_records (
    record_id     BIGINT UNSIGNED                       NOT NULL AUTO_INCREMENT,
    user_id       INT                                   NOT NULL,
    scope         VARCHAR(64)                           NOT NULL COMMENT '如 goal.create / entry.create / diary.create / comment.create',
    idem_key      VARCHAR(80)                           NOT NULL,
    request_hash  CHAR(64)                              NOT NULL COMMENT 'SHA-256(规范化请求体)',
    response_json JSON                                  NULL DEFAULT NULL,
    status        ENUM('processing','completed')        NOT NULL DEFAULT 'processing',
    created_at    DATETIME(3)                           NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (record_id),
    UNIQUE KEY uk_idem (user_id, scope, idem_key),
    KEY idx_idem_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '幂等记录：同键同载荷返回同一结果，同键不同载荷 409';
