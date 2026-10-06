// 微信 jscode2session 错误码 → 业务错误的映射表。
//
// 为什么单独成模块：这是登录链路上最容易误判的一段逻辑 ——
// 把「服务端配置错」（appid/appsecret 不匹配）当成「用户凭证错」，
// 结果运维看到的是「invalid appsecret」这种像用户输错密码的提示，上线时极难排查。
// 抽出来之后可以脱离网络做单元测试（scripts/dev/verify-launch-ready.js 会直接跑这张表的断言）。

const { ApiError } = require("../errors");

// 需要管理员去微信公众平台改配置的错误码 —— 改代码、让用户重试都没有用
const CONFIG_ERRCODES = new Set([40013, 40125]);

/**
 * @param {object} payload 微信 /sns/jscode2session 的响应体
 * @returns {ApiError}
 */
function mapWechatSessionError(payload) {
  const errcode = Number(payload && payload.errcode);
  const errmsg = (payload && payload.errmsg) || "微信登录校验失败";
  const rid = /rid:\s*([0-9a-f-]+)/i.exec(errmsg);
  const detail = rid ? `（rid ${rid[1]}）` : "";

  switch (errcode) {
    // appid / appsecret 不匹配 —— 服务端配置问题，必须改环境变量
    case 40013:
    case 40125:
      console.error(`[wechat-login] 微信配置无效 errcode=${errcode}：${errmsg}`);
      return new ApiError(
        503,
        "WECHAT_CONFIG_INVALID",
        `微信登录配置无效（errcode ${errcode}），请核对 WECHAT_APPID / WECHAT_SECRET 是否与该小程序匹配`
      );

    // code 无效 / 已使用 / 过期 —— 用户侧问题，重新 wx.login 即可
    case 40029:
    case 40163:
      return new ApiError(401, "WECHAT_CODE_INVALID", "微信登录凭证无效或已过期，请重试");

    // 接口调用频率超限
    case 45011:
      return new ApiError(429, "WECHAT_RATE_LIMITED", "微信登录请求过于频繁，请稍后重试");

    // 用户被微信标记为高风险 / 已封禁
    case 40226:
      return new ApiError(403, "WECHAT_USER_RISK", "该微信账号暂时无法登录");

    // 微信侧系统繁忙
    case -1:
      return new ApiError(502, "WECHAT_SYSTEM_BUSY", "微信服务繁忙，请稍后重试");

    default:
      console.warn(`[wechat-login] 微信返回未识别错误 errcode=${errcode}：${errmsg}`);
      return new ApiError(
        502,
        "WECHAT_UPSTREAM_ERROR",
        `微信登录失败（errcode ${errcode || "unknown"}）${detail}`
      );
  }
}

module.exports = { mapWechatSessionError, CONFIG_ERRCODES };
