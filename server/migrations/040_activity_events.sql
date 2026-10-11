-- 活动中心（动态流，F4）数据模型 —— 仿 036 diary_update_events。
--
-- 定位：关系内的站内动态收件箱（recipient 恒为伴侣），每次写入都在主业务事务内完成；
-- 事件本身是消费去重键：UNIQUE(recipient_id, event_type, resource_id)，
-- 重复触发走 ON DUPLICATE KEY UPDATE 把已 seen 的事件重新置 unread（应当再次被提醒）。
--
-- 隐私与生命周期：
--   * 只存最小上下文（金额分 + 截断标题快照），不落正文/图片/_token；
--   * 解绑在 unbind 事务内按 relationship_id 整体 revoke，读路径同时按
--     「当前 active 关系」重校验，失效关系的事件不返回；
--   * 与 housework/goals 一致，身份列不建外键。
--
-- 幂等：建表 IF NOT EXISTS，重复执行结果相同。
-- 回滚说明：默认禁止 DROP；确需清理时 DROP activity_events。

CREATE TABLE IF NOT EXISTS activity_events (
    event_id       CHAR(36)                                                    NOT NULL PRIMARY KEY,
    relationship_id BIGINT UNSIGNED                                            NOT NULL,
    recipient_id   BIGINT UNSIGNED                                             NOT NULL COMMENT '收件人=伴侣',
    actor_id       BIGINT UNSIGNED                                             NOT NULL COMMENT '动作发起人',
    event_type     ENUM('bill.created','bill.deleted','goal.entry','goal.reversed',
                        'plan.entry','housework.recorded','recurring.confirmed') NOT NULL,
    resource_id    VARCHAR(64)                                                 NOT NULL COMMENT '业务资源 ID（账单/流水/计划/记录）',
    amount_cents   BIGINT                                                      NULL DEFAULT NULL COMMENT '金额（分）；家务等无金额事件为 NULL',
    title_snapshot VARCHAR(64)                                                 NULL DEFAULT NULL COMMENT '截断标题快照',
    state          ENUM('unread','seen','revoked')                             NOT NULL DEFAULT 'unread',
    created_at     TIMESTAMP                                                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_activity (recipient_id, event_type, resource_id),
    KEY idx_activity_inbox (recipient_id, state, created_at),
    KEY idx_activity_relationship (relationship_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '活动中心动态：伴侣收件箱，解绑整体 revoke';
