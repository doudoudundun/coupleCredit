-- 037：小记互动（v0.4）—— 贴纸回应 / 评论带图 / 已读回执
--
-- 纯增量迁移：一张新表 + 三个扩列/扩值，不动任何现有列语义；
-- 生产人工执行一次（与既有惯例一致），出问题无需回滚表结构。

-- 1) 贴纸回应：一人一帖一条（UNIQUE），换贴 = UPDATE，取消 = DELETE
CREATE TABLE IF NOT EXISTS diary_reactions (
    reaction_id CHAR(36)                                    NOT NULL,
    diary_id    CHAR(36)                                    NOT NULL,
    space_id    CHAR(36)                                    NOT NULL,
    user_id     INT                                         NOT NULL COMMENT '贴贴纸的人（帖子作者的伴侣）',
    emoji       VARCHAR(16)                                 NOT NULL COMMENT '白名单内的单个贴纸',
    created_at  DATETIME(3)                                 NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)                                 NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (reaction_id),
    UNIQUE KEY uq_diary_reaction (diary_id, user_id),
    KEY idx_diary_reaction_space (space_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '小记贴纸回应：空间 active 时仅伴侣可贴，不能给自己的帖子贴';

-- 2) 未读事件类型：新增 reaction（对方贴/换贴纸；取消贴纸时按 diary_id+reaction 撤销）
ALTER TABLE diary_update_events
    MODIFY COLUMN event_type ENUM('published','reply','reaction') NOT NULL;

-- 3) 评论媒体：purpose 扩值 comment（一条评论最多 1 张，绑定记录在 comment_id）
ALTER TABLE diary_media
    MODIFY COLUMN purpose ENUM('diary','background','comment') NOT NULL COMMENT 'diary=帖子配图 background=空间背景 comment=评论配图';
ALTER TABLE diary_media
    ADD COLUMN comment_id CHAR(36) NULL DEFAULT NULL AFTER diary_id,
    ADD KEY idx_diary_media_comment (comment_id);

-- 4) 已读回执：非作者伴侣首次打开详情时落一次（NULL=对方还没看过）
ALTER TABLE diary_posts
    ADD COLUMN partner_seen_at DATETIME(3) NULL DEFAULT NULL COMMENT '伴侣首次查看时间（已读回执，只写一次）';
