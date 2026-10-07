// Short-lived viewer-bound capability; content route rechecks current ACL on every read.
const crypto = require("crypto");
const DEFAULT_TTL_MS = 5 * 60 * 1000;
function signature(secret, payload) { return crypto.createHmac("sha256", secret).update(payload).digest("base64url"); }
function signMediaToken({ secret, mediaId, variant, authVersion, viewerId, ttlMs = DEFAULT_TTL_MS }) {
  if (!Number.isSafeInteger(Number(viewerId)) || Number(viewerId) < 1) throw new Error("MEDIA_VIEWER_REQUIRED");
  const exp = Date.now() + Math.min(DEFAULT_TTL_MS, Math.max(1, ttlMs));
  const payload = `${mediaId}.${variant}.${exp}.${authVersion}.${viewerId}`;
  return Buffer.from(`${exp}.${viewerId}.${signature(secret, payload)}`).toString("base64url");
}
function verifyMediaToken({ secret, mediaId, variant, authVersion, token }) {
  if (typeof token !== "string" || token.length > 256) return null;
  const parts = Buffer.from(token, "base64url").toString().split(".");
  if (parts.length !== 3) return null;
  const [expiry, viewer, sig] = parts, exp = Number(expiry), viewerId = Number(viewer);
  if (!Number.isSafeInteger(exp) || exp <= Date.now() || exp > Date.now() + DEFAULT_TTL_MS || !Number.isSafeInteger(viewerId) || viewerId < 1) return null;
  const expected = signature(secret, `${mediaId}.${variant}.${exp}.${authVersion}.${viewer}`);
  if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  return { viewerId };
}
function buildMediaUrl(options) {
  return `/api/diary-media/${options.mediaId}/content?variant=${options.variant}&st=${encodeURIComponent(signMediaToken(options))}`;
}
module.exports = { signMediaToken, verifyMediaToken, buildMediaUrl };
