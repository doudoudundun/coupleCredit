// 小记媒体签名 URL（v0.2）
//
// 小程序 <image>/wx.previewImage 不能携带 Authorization 头，因此媒体内容端点
// （GET /api/diary-media/:id/content）支持 ?st= 短时效签名：
//   st = base64url(`${exp}.${sig}`)，sig = HMAC-SHA256(secret, "mediaId.variant.exp.authVersion")
// 签名绑定授权版本（auth_version）：解绑/删除时版本递增，已外发的签名 URL 立即失效。
// 不签发永久 URL；默认 12 小时过期。
const crypto = require("crypto");

const DEFAULT_TTL_MS = 12 * 60 * 60 * 1000;

function base64url(buffer) {
  return Buffer.from(buffer).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function hmac(secret, text) {
  return crypto.createHmac("sha256", secret).update(text).digest();
}

/**
 * @param {object} opts { secret, mediaId, variant, authVersion, ttlMs? }
 * @returns {string} st 参数值
 */
function signMediaToken({ secret, mediaId, variant, authVersion, ttlMs }) {
  const exp = Date.now() + (ttlMs || DEFAULT_TTL_MS);
  const payload = `${mediaId}.${variant}.${exp}.${authVersion}`;
  const sig = base64url(hmac(secret, payload));
  return base64url(`${exp}.${sig}`);
}

function safeEqual(a, b) {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

/**
 * 校验 st 参数。返回 true/false（不抛错，调用方统一 403/404）。
 */
function verifyMediaToken({ secret, mediaId, variant, authVersion, token }) {
  if (!token || typeof token !== "string") return false;
  let decoded;
  try {
    decoded = Buffer.from(token.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8");
  } catch (_error) {
    return false;
  }
  const dot = decoded.indexOf(".");
  if (dot <= 0) return false;
  const exp = Number(decoded.slice(0, dot));
  const sig = decoded.slice(dot + 1);
  if (!Number.isFinite(exp) || exp < Date.now()) return false;
  const expected = base64url(hmac(secret, `${mediaId}.${variant}.${exp}.${authVersion}`));
  return safeEqual(sig, expected);
}

/** 组装内容端点 URL（相对路径，前端 resolveImageUrl 自动补全 baseUrl）。 */
function buildMediaUrl({ secret, mediaId, variant, authVersion, ttlMs }) {
  const st = signMediaToken({ secret, mediaId, variant, authVersion, ttlMs });
  return `/api/diary-media/${mediaId}/content?variant=${variant}&st=${encodeURIComponent(st)}`;
}

module.exports = { signMediaToken, verifyMediaToken, buildMediaUrl };
