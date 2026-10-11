-- 情侣打卡 / 习惯共建 数据模型
--
-- 空间语义与共同目标（035）一致：
--   * habits.space_id 指向 housework_spaces（有 active 关系 → 共享空间，否则个人空间），
--     快照语义不建外键；解绑冻结空间后读路径不再把该空间当「当前空间」，历史行保留。
--   * habit_checkins 用 UNIQUE(habit_id, user_id, checkin_on) 在数据库层兜底「每人每天一次」，
--     重复打卡靠撞键幂等返回已有记录。
--   * 打卡日期由服务端按空间时区计算，客户端不传日期。
--
-- 同时把活动中心 event_type 枚举扩展出 'habit.checkin'（打卡成功给伴侣投递动态）。
--
-- 幂等：建表 IF NOT EXISTS；ALTER MODIFY COLUMN 重复执行结果相同。
-- 回滚说明：默认禁止 DROP；确需清理时先 DROP habit_checkins 再 DROP habits，
--   并把 activity_events.event_type 枚举改回 040 口径。

CREATE TABLE IF NOT EXISTS habits (
    habit_id    CHAR(36)                                NOT NULL,
    space_id    CHAR(36)                                NOT NULL COMMENT '个人空间或共享空间（housework_spaces.space_id 快照语义，无 FK）',
    creator_id  BIGINT                                  NOT NULL,
    title       VARCHAR(64)                             NOT NULL,
    icon        VARCHAR(16)                             NULL DEFAULT NULL COMMENT 'emoji',
    status      ENUM('active','archived')               NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP                               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (habit_id),
    KEY idx_habits_space (space_id, status),
    KEY idx_habits_creator (creator_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '情侣打卡习惯：空间内双方共建';

CREATE TABLE IF NOT EXISTS habit_checkins (
    id          BIGINT UNSIGNED                         NOT NULL AUTO_INCREMENT,
    habit_id    CHAR(36)                                NOT NULL,
    user_id     BIGINT                                  NOT NULL,
    checkin_on  DATE                                    NOT NULL COMMENT '空间时区的当天日期',
    created_at  TIMESTAMP                               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_habit_checkin_day (habit_id, user_id, checkin_on),
    KEY idx_habit_checkins_user (user_id, checkin_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '打卡记录：每人每天每习惯一次，UNIQUE 兜底幂等';

ALTER TABLE activity_events
    MODIFY COLUMN event_type ENUM('bill.created','bill.deleted','goal.entry','goal.reversed',
                                  'plan.entry','housework.recorded','recurring.confirmed',
                                  'habit.checkin') NOT NULL;
