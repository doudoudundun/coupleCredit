-- 内容安全（UGC 审核）基础设施
--
-- 背景：小程序需要向微信声明「用户生成内容」并接入内容安全能力。
-- 本项目实际的 UGC 面只有两处：
--   1) 用户头像（pages/my 的 chooseAvatar → wx.uploadFile）
--   2) 用户资料文本（注册时的 username、可修改的 nickname）
-- 账单的分类/归属/日期全是 picker 点选，不属于 UGC。
--
-- 审核链路：
--   文本 → msg_sec_check（同步，立即知道结果）
--   图片 → media_check_async（异步，结果 30 分钟内经「消息推送」回调）
--
-- 因此头像需要一个「审核状态」维度：上传后先不可见，回调 pass 后才可见。
--
-- 部署顺序：本迁移必须先于对应服务端版本上线，否则新代码读 avatar_status 会报错。

-- 1) 头像审核状态
--    默认 pending 而不是 approved：新上传的头像必须先过审。
--    存量数据在下面回填为 approved，避免历史用户头像集体消失。
ALTER TABLE users
  ADD COLUMN avatar_status ENUM('pending', 'approved', 'rejected')
    NOT NULL DEFAULT 'pending' AFTER avatar;

-- 存量头像视为已通过：它们是在本次审核能力上线前上传的，
-- 没有 trace_id 可以补审，一律打回会让所有老用户头像变空。
UPDATE users SET avatar_status = 'approved' WHERE avatar IS NOT NULL AND avatar <> '';

-- 没有头像的用户也回填 approved：没有图可审，留 pending 只会让状态语义含混。
-- 注：列的默认值故意保留 pending（fail-safe）—— 将来若有人新增写 avatar 的路径
-- 却忘记同步状态，表现是「头像不显示」而不是「未审核即对外可见」，更容易被发现。
-- 「avatar 为 NULL 时按 approved 处理」这个规则在读取端统一实现
-- （utils/queryHelpers.loadPartnerProfile 与 routes/security.js）。
UPDATE users SET avatar_status = 'approved' WHERE avatar IS NULL OR avatar = '';

-- 2) 审核任务表
--    media_check_async 的回调只带回 trace_id，不带任何业务标识，
--    所以必须由服务端在发起审核时把 trace_id 与「哪个用户的哪张图」关联起来。
CREATE TABLE IF NOT EXISTS content_checks (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id        VARCHAR(64)     NOT NULL COMMENT '微信返回的任务 id，回调按它匹配',
  user_id         INT             NOT NULL,
  biz_type        ENUM('avatar', 'text') NOT NULL,
  -- 图片存 imageUrl；文本存内容摘要（截断，不落全文，减少敏感数据留存）
  biz_ref         VARCHAR(512)    NULL DEFAULT NULL,
  status          ENUM('pending', 'approved', 'rejected', 'failed')
                    NOT NULL DEFAULT 'pending',
  suggest         VARCHAR(16)     NULL DEFAULT NULL COMMENT 'pass / risky / review',
  label           INT             NULL DEFAULT NULL COMMENT '命中标签枚举值',
  raw_response    TEXT            NULL DEFAULT NULL COMMENT '微信原始返回，排障用',
  created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at     TIMESTAMP       NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_checks_trace (trace_id),
  KEY idx_content_checks_user (user_id, biz_type, created_at),
  KEY idx_content_checks_status (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '内容安全异步审核任务（media_check_async 回调关联表）';
