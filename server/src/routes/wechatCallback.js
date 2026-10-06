const express = require("express");
const {
  verifyPlainSignature,
  verifyMsgSignature,
  decryptWechatPayload,
  extractEncrypt,
  normalizeMessage
} = require("../utils/wechatCrypto");

/**
 * 微信「消息推送」回调。
 *
 * 用途：media_check_async（图片内容安全）的结果只通过消息推送下发，
 * 这是唯一能拿到头像审核结论的通道。
 *
 * ── 两个必须注意的点 ─────────────────────────────────────────
 * 1) 本路由必须挂载在全局 JWT 鉴权之前 —— 微信不会带 Authorization。
 * 2) 返回体必须是纯文本 "success"。微信在 5 秒内没收到 success 会重试，
 *    连续失败还可能停用推送，所以业务异常也不能让响应失败（见 POST 处理）。
 *
 * ── 三种加解密模式都要能收 ───────────────────────────────────
 * 后台的「消息加解密方式」是单选，选错会让审核结果收不到，而症状是
 * 「头像一直审核中」这种静默故障，所以三种全部兼容：
 *
 *   安全模式  报文只有 Encrypt        → msg_signature 验签 + 解密
 *   兼容模式  明文与密文共存          → 同上（能取到 Encrypt）
 *   明文模式  报文里没有 Encrypt      → **必须**用 signature 验签后才处理
 *
 * ── 明文分支为什么也必须验签 ─────────────────────────────────
 * 明文报文里直接带着 suggest="pass"，若不验签，任何人都能 POST 一份假回调，
 * 把违规图片洗成「已通过」—— 那等于没有审核。所以明文分支强制校验 signature
 * （sha1 排序拼接 token/timestamp/nonce），没签名或签名不对一律 401。
 * 注意：这不比加密分支弱，两者都要求攻击者掌握 Token。
 *
 * ── GET 校验（后台点保存时触发）为什么三种组合都兼容 ─────────
 * 微信官方文档「URL 校验」一节只写了 signature + echostr，没有按模式区分；
 * 而安全模式的 POST 示例里 msg_signature 与 signature 是同时出现的。
 *
 * 实际观测（2026-09-23 00:38，生产日志）：来源 118.25.155.162（腾讯云，
 * UA Mozilla/4.0 + HTTP/1.0，即微信服务器）、参数用 signature、echostr 是
 * 19 位**明文**数字，回显后后台保存成功 —— 而当时后台显示「安全模式」。
 * 结论：安全模式的 URL 校验大概率是 signature + 明文 echostr，且这一步
 * **完全不使用 EncodingAESKey**，所以「保存成功」只证明 Token 一致，
 * 不能证明密钥一致（密钥要等第一条真实加密回调才能验证）。
 *
 * 为了不把结论押在这个观测上，三种组合全部兼容：先验签，再尝试解密，
 * 能解开就返回明文，解不开就按明文原样回显。
 */

/** 从 req 取原始报文体，兼容 express.json 已解析与未解析两种情况 */
function rawBodyOf(req) {
  if (typeof req.body === "string") return req.body;
  if (Buffer.isBuffer(req.body)) return req.body.toString("utf8");
  if (req.body && typeof req.body === "object") return JSON.stringify(req.body);
  return "";
}

function createWechatCallbackRouter({ config, contentSecurity, pool }) {
  const router = express.Router();

  function requireConfigured(res) {
    if (!config.wechatMsgToken || !config.wechatEncodingAESKey) {
      res.status(503).type("text/plain").send("微信消息推送未配置：缺少 WECHAT_MSG_TOKEN / WECHAT_ENCODING_AES_KEY");
      return false;
    }
    return true;
  }

  // 微信后台保存「消息推送」配置时会先 GET 校验 URL 可达性
  router.get("/callback", (req, res) => {
    if (!requireConfigured(res)) return;

    const { signature, msg_signature: msgSignature, timestamp, nonce, echostr } = req.query;
    if (!timestamp || !nonce || !echostr) {
      return res.status(400).type("text/plain").send("缺少 timestamp/nonce/echostr");
    }

    // 组合 A：带 msg_signature —— echostr 一定是密文，验签后解密回显明文
    if (msgSignature) {
      const ok = verifyMsgSignature({
        token: config.wechatMsgToken,
        timestamp,
        nonce,
        encrypt: echostr,
        signature: msgSignature
      });
      if (!ok) return res.status(401).type("text/plain").send("签名校验失败");

      try {
        const { message } = decryptWechatPayload(echostr, config.wechatEncodingAESKey);
        return res.type("text/plain").send(message);
      } catch (e) {
        console.error("[wechat-callback] echostr 解密失败:", e.message);
        return res.status(400).type("text/plain").send("echostr 解密失败");
      }
    }

    // 组合 B/C：只有 signature。先验签（证明来自微信），再判断 echostr 是密文还是明文。
    if (!signature) return res.status(400).type("text/plain").send("缺少 signature / msg_signature");
    if (!verifyPlainSignature({ token: config.wechatMsgToken, timestamp, nonce, signature })) {
      return res.status(401).type("text/plain").send("签名校验失败");
    }

    try {
      const { message } = decryptWechatPayload(echostr, config.wechatEncodingAESKey);
      return res.type("text/plain").send(message);
    } catch (_e) {
      // 解不开 = echostr 本身就是明文字符串（明文模式），微信要求原样返回
      return res.type("text/plain").send(echostr);
    }
  });

  router.post("/callback", express.text({ type: "*/*" }), async (req, res) => {
    // 微信要求返回 "success"；先承接再处理，避免任何路径漏掉响应
    const respondSuccess = () => res.type("text/plain").send("success");

    if (!config.wechatMsgToken || !config.wechatEncodingAESKey) {
      console.error("[wechat-callback] 未配置 WECHAT_MSG_TOKEN / WECHAT_ENCODING_AES_KEY，回调被丢弃");
      // 已配置错误时返回 success 会让微信停止重试；这里返回 503 让运维能立刻发现配置缺失
      return res.status(503).type("text/plain").send("微信消息推送未配置");
    }

    const { signature, msg_signature: msgSignature, timestamp, nonce } = req.query;
    if (!timestamp || !nonce) {
      return res.status(400).type("text/plain").send("缺少 timestamp/nonce");
    }

    const rawBody = rawBodyOf(req);
    const encrypt = extractEncrypt(rawBody);
    let message;

    if (encrypt) {
      // 安全模式 / 兼容模式：先验签再解密。签名不合法直接拒绝，
      // 不能继续解密——否则解密错误信息会变成攻击者可用的 oracle。
      if (!msgSignature) {
        return res.status(400).type("text/plain").send("缺少 msg_signature");
      }
      if (!verifyMsgSignature({ token: config.wechatMsgToken, timestamp, nonce, encrypt, signature: msgSignature })) {
        console.warn("[wechat-callback] msg_signature 校验失败，拒绝处理");
        return res.status(401).type("text/plain").send("签名校验失败");
      }
      try {
        message = decryptWechatPayload(encrypt, config.wechatEncodingAESKey).message;
      } catch (e) {
        console.error("[wechat-callback] 解密失败:", e.message);
        return res.status(400).type("text/plain").send("解密失败");
      }
    } else {
      // 明文模式：报文里没有 Encrypt，只能靠 signature 证明来自微信（见文件头说明）
      if (!signature) {
        console.warn("[wechat-callback] 报文未加密且无 signature，拒绝处理（后台建议改选安全模式）");
        return res.status(400).type("text/plain").send("报文体未加密且缺少 signature");
      }
      if (!verifyPlainSignature({ token: config.wechatMsgToken, timestamp, nonce, signature })) {
        console.warn("[wechat-callback] 明文 signature 校验失败，拒绝处理");
        return res.status(401).type("text/plain").send("签名校验失败");
      }
      message = rawBody;
    }

    const event = normalizeMessage(message);
    if (event.event !== "wxa_media_check") {
      // 目前只用消息推送接收内容安全结果，其他事件类型直接确认接收
      return respondSuccess();
    }

    try {
      const outcome = await contentSecurity.applyMediaCheckResult({
        traceId: event.traceId,
        errcode: event.errcode,
        suggest: event.suggest,
        label: event.label,
        raw: message,
        pool
      });
      console.log(
        `[wechat-callback] trace_id=${event.traceId} suggest=${event.suggest} → ${outcome.reason}` +
          (outcome.userId ? ` user=${outcome.userId}` : "")
      );
    } catch (e) {
      // 业务处理失败仍返回 success：微信重试也无法修复（如数据库不可用），
      // 反而可能因连续失败被停用推送。这里以日志暴露问题。
      console.error(`[wechat-callback] 处理审核结果失败 trace_id=${event.traceId}:`, e.message);
    }

    return respondSuccess();
  });

  return router;
}

module.exports = { createWechatCallbackRouter };
