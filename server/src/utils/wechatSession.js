/**
 * 用 wx.login 的 code 换取 openid（jscode2session）。
 *
 * auth.js 的微信登录端点里有一份同样的调用，但那处耦合了登录流程的错误语义。
 * 内容安全需要的是「拿到一个 openid 用于送审」，与登录无关（现换的 openid 不落库），
 * 所以这里单独提供一份，并复用同一张错误码映射表，保证排查口径一致。
 */

const { mapWechatSessionError } = require("./wechatErrors");

const JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
const REQUEST_TIMEOUT_MS = 8000;

async function getWechatOpenidByCode(code, config, fetchImpl) {
  if (!config.wechatAppId || !config.wechatSecret) {
    throw new Error("服务端缺少 WECHAT_APPID / WECHAT_SECRET");
  }
  if (!code) throw new Error("缺少 code");

  const url =
    `${JSCODE2SESSION_URL}?appid=${encodeURIComponent(config.wechatAppId)}` +
    `&secret=${encodeURIComponent(config.wechatSecret)}` +
    `&js_code=${encodeURIComponent(code)}&grant_type=authorization_code`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let payload;
  try {
    const resp = await (fetchImpl || fetch)(url, { method: "GET", signal: controller.signal });
    payload = await resp.json();
  } catch (e) {
    throw new Error(e && e.name === "AbortError" ? "微信服务请求超时" : "微信服务请求失败");
  } finally {
    clearTimeout(timer);
  }

  if (!payload || payload.errcode || !payload.openid) {
    // 复用统一映射表：配置类错误码（40013/40125）会被映射为 503，而不是伪装成用户凭证错误
    throw mapWechatSessionError(payload || {});
  }
  return payload.openid;
}

module.exports = { getWechatOpenidByCode };
