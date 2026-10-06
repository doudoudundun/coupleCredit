const express = require("express");
const { ApiError } = require("../errors");
const { SCENE } = require("../services/contentSecurity");

/**
 * 内容安全相关端点（均需登录）。
 *
 * 注意本路由返回 200 + { pass } 而不是 4xx：
 * 「这段文本合不合格」是一次查询，不是操作失败。若返回 4xx，客户端统一错误处理
 * 会把它当异常弹 toast，而实际场景是「边输入边提示」，需要拿到结构化结果自行渲染。
 */
function createSecurityRouter({ contentSecurity, pool }) {
  const router = express.Router();

  router.post("/check-text", async (req, res, next) => {
    try {
      const userId = req.userId;
      const rawText = typeof req.body.text === "string" ? req.body.text : "";
      const code = typeof req.body.code === "string" ? req.body.code : "";
      const scene = Number(req.body.scene) || SCENE.PROFILE;

      if (!rawText.trim()) {
        throw new ApiError(400, "INVALID_REQUEST", "text 必填");
      }

      // 故意不传 limits：沿用服务端默认值（1~50，与 users.username/nickname 的列宽一致）。
      // 本次改动只做内容安全，不趁机收紧原本合法的输入。
      const result = await contentSecurity.checkText({ userId, content: rawText, scene, code });

      res.json({
        ok: true,
        message: result.pass ? "内容合规" : result.message || "内容未通过安全审核",
        data: {
          pass: result.pass,
          suggest: result.suggest,
          label: result.label,
          // degraded=true 表示微信侧没给出结论（未配置/无 openid/上游故障），
          // 本次按放行处理。客户端可据此提示「审核暂不可用」，但不应阻断用户。
          degraded: result.degraded,
          reason: result.reason
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // 头像审核状态：供客户端展示「审核中 / 已被驳回」
  router.get("/avatar-status", async (req, res, next) => {
    try {
      const userId = req.userId;
      // pool 由 createSecurityRouter({ pool }) 注入。
      // 2026-09-23：原实现读 req.app.locals.authPool，但 index.js 从未设置过该变量
      // → pool 为 undefined → 本接口在生产恒定 500（小程序「我的」页每次进入都会调它）。
      const [rows] = await pool.execute(
        "SELECT avatar, avatar_status FROM users WHERE id = ? LIMIT 1",
        [userId]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const row = rows[0];
      // 没有头像时，状态没有意义，一律报 approved 免得客户端显示「审核中」却无图可审
      const status = row.avatar ? row.avatar_status : "approved";

      res.json({
        ok: true,
        data: {
          avatarStatus: status,
          // 只有审核中/被驳回才需要提示用户
          shouldNotify: Boolean(row.avatar) && status !== "approved"
        }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createSecurityRouter };
