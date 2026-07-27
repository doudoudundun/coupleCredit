-- 为微信小程序登录新增 openid 绑定
-- 在 users 表上加 wechat_openid 列，并建唯一索引（一个 openid 绑定一个账号）
--
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS（8.0 之前），
-- 若该列已存在，重复执行会报错，可忽略对应错误或手动跳过。

ALTER TABLE users
  ADD COLUMN wechat_openid VARCHAR(64) NULL DEFAULT NULL AFTER invite_code;

ALTER TABLE users
  ADD UNIQUE INDEX uniq_wechat_openid (wechat_openid);
