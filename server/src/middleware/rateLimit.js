const rateLimit = require("express-rate-limit");

const standardLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 2000,
  standardHeaders: true,
  legacyHeaders: false,
  handler: (_req, res) => {
    res.status(429).json({ ok: false, error: { code: "RATE_LIMITED", message: "请求过于频繁，请稍后再试" } });
  },
});

const authLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 50,
  standardHeaders: true,
  legacyHeaders: false,
  handler: (_req, res) => {
    res.status(429).json({ ok: false, error: { code: "RATE_LIMITED", message: "登录尝试过于频繁，请15分钟后再试" } });
  },
});

const strictLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  handler: (_req, res) => {
    res.status(429).json({ ok: false, error: { code: "RATE_LIMITED", message: "请求过于频繁" } });
  },
});

const aiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 15,
  standardHeaders: true,
  legacyHeaders: false,
  handler: (_req, res) => {
    res.status(429).json({ ok: false, error: { code: "RATE_LIMITED", message: "AI 请求过于频繁，请稍后再试" } });
  },
});

module.exports = { standardLimiter, authLimiter, strictLimiter, aiLimiter };
