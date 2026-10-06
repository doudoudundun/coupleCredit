/**
 * 微信接口调用凭证（access_token）获取与缓存。
 *
 * 用途：msg_sec_check（文本内容安全）与 media_check_async（图片内容安全）
 * 都需要 access_token。
 *
 * 为什么优先用 /cgi-bin/stable_token：
 *   普通 /cgi-bin/token 每次调用都会签发新 token 并让旧的失效。
 *   本项目的 AppID 同时被 Android 端与小程序端使用（同一开放平台账号），
 *   两边若各自刷新会互相把对方的 token 顶掉，表现为随机的 40001 invalid credential。
 *   stable_token 对同一 appid 在有效期内返回同一个 token，且与 /cgi-bin/token
 *   互相隔离，从根上避免互相踢下线。
 *
 * 缓存策略：内存缓存，提前 5 分钟过期，并对并发请求做去重（同时多个请求只发一次）。
 *
 * Node 的 fetch（undici）默认不读 HTTP(S)_PROXY 环境变量，因此这里直连
 * api.weixin.qq.com；若将来改成依赖代理，微信相关功能会静默变成 502。
 */

const WECHAT_API_BASE = "https://api.weixin.qq.com";
const EXPIRY_SAFETY_MARGIN_MS = 5 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 8000;

class WechatAccessTokenError extends Error {
  constructor(message, { code } = {}) {
    super(message);
    this.name = "WechatAccessTokenError";
    this.code = code;
  }
}

// access_token 失效的错误码，调用方遇到时应强制刷新后重试一次
const TOKEN_INVALID_ERRCODES = new Set([40001, 40014, 42001]);

function createWechatAccessTokenProvider({ appId, appSecret, fetchImpl } = {}) {
  const doFetch = fetchImpl || fetch;

  let token = null;
  let expiresAt = 0;
  let inflight = null;

  function configured() {
    return Boolean(appId && appSecret);
  }

  async function callWechatApi(apiPath, { method = "POST", body } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let payload;
    try {
      const init = { method, signal: controller.signal };
      if (body !== undefined) {
        init.headers = { "Content-Type": "application/json" };
        init.body = JSON.stringify(body);
      }
      const resp = await doFetch(`${WECHAT_API_BASE}${apiPath}`, init);
      payload = await resp.json();
    } catch (e) {
      throw new WechatAccessTokenError(
        e && e.name === "AbortError" ? "获取微信凭证超时" : "无法连接微信服务"
      );
    } finally {
      clearTimeout(timer);
    }

    if (!payload || payload.access_token == null) {
      // 40125 invalid appsecret / 40013 invalid appid 都属服务端配置问题
      const errcode = payload && payload.errcode;
      throw new WechatAccessTokenError(
        `获取微信凭证失败（errcode ${errcode == null ? "unknown" : errcode}）`,
        { code: errcode }
      );
    }
    return payload;
  }

  function remember(payload) {
    token = payload.access_token;
    const expiresInMs = Number(payload.expires_in || 7200) * 1000;
    // 至少保留 1 分钟缓存，避免 expires_in 异常偏小导致每次都打微信
    expiresAt = Date.now() + Math.max(expiresInMs - EXPIRY_SAFETY_MARGIN_MS, 60 * 1000);
    return token;
  }

  /**
   * 取 access_token。
   * @param {object}  [options]
   * @param {boolean} [options.forceRefresh] 忽略缓存强制刷新（token 被判定失效时用）
   * @returns {Promise<string>}
   */
  async function get({ forceRefresh = false } = {}) {
    if (!configured()) {
      throw new WechatAccessTokenError("服务端缺少 WECHAT_APPID / WECHAT_SECRET");
    }
    if (!forceRefresh && token && Date.now() < expiresAt) {
      return token;
    }

    // 并发去重：多个请求同时发现缓存失效时，只让第一个真正打微信
    if (inflight) return inflight;

    inflight = (async () => {
      try {
        return remember(
          await callWechatApi("/cgi-bin/stable_token", {
            body: {
              grant_type: "client_credential",
              appid: appId,
              secret: appSecret,
              force_refresh: Boolean(forceRefresh)
            }
          })
        );
      } catch (stableError) {
        // stable_token 并非所有账号类型都开放，失败时回退到经典接口（GET + query）
        try {
          return remember(
            await callWechatApi(
              `/cgi-bin/token?grant_type=client_credential&appid=${encodeURIComponent(appId)}&secret=${encodeURIComponent(appSecret)}`,
              { method: "GET" }
            )
          );
        } catch (_fallbackError) {
          // 回退也失败时抛原始错误，保留 stable_token 的具体 errcode 便于诊断
          throw stableError;
        }
      }
    })();

    try {
      return await inflight;
    } finally {
      inflight = null;
    }
  }

  /** 主动丢弃缓存（测试与排障用） */
  function invalidate() {
    token = null;
    expiresAt = 0;
  }

  function snapshot() {
    const fresh = Boolean(token) && Date.now() < expiresAt;
    return {
      configured: configured(),
      cached: fresh,
      expiresInMs: fresh ? expiresAt - Date.now() : 0
    };
  }

  return { get, invalidate, snapshot, configured };
}

module.exports = {
  createWechatAccessTokenProvider,
  WechatAccessTokenError,
  TOKEN_INVALID_ERRCODES,
  WECHAT_API_BASE
};
