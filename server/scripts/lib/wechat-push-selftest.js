/**
 * 微信消息推送配置的本地自检。
 *
 * 为什么需要它：消息推送的验签/解密只有在真实回调到来时才会被执行，
 * 而配置错（Token 抄错、EncodingAESKey 少一位、后台没开安全模式）通常要等到
 * 用户上传头像后「审核结果永远不回来」才会发现，排查成本很高。
 *
 * 这里在本地用与微信服务端相同的算法构造一段加密报文，再走一遍真实的
 * 验签 → 解密 → 归一化 → 结果落库判定流程，把问题提前暴露在配置阶段。
 * 全程不需要公网、不访问微信。
 */

const crypto = require("crypto");
const path = require("path");

const { computeMsgSignature, decryptWechatPayload, extractEncrypt, normalizeMessage } = require(
  path.resolve(__dirname, "../../src/utils/wechatCrypto.js")
);

/** 镜像微信服务端的加密过程，仅用于自检 */
function encryptLikeWechat(message, appid, encodingAESKey) {
  const key = Buffer.from(encodingAESKey + "=", "base64");
  const iv = key.subarray(0, 16);
  const msgBuf = Buffer.from(message, "utf8");
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(msgBuf.length, 0);

  const raw = Buffer.concat([crypto.randomBytes(16), lenBuf, msgBuf, Buffer.from(appid, "utf8")]);
  const padLength = 32 - (raw.length % 32); // 整除时得 32，与官方实现一致
  const padded = Buffer.concat([raw, Buffer.alloc(padLength, padLength)]);

  const cipher = crypto.createCipheriv("aes-256-cbc", key, iv);
  cipher.setAutoPadding(false);
  return Buffer.concat([cipher.update(padded), cipher.final()]).toString("base64");
}

function buildEncryptedCallback({ token, encodingAESKey, appid, message, timestamp, nonce, wrap }) {
  const encrypt = encryptLikeWechat(message, appid, encodingAESKey);
  const signature = computeMsgSignature({ token, timestamp, nonce, encrypt });
  const body =
    wrap === "json"
      ? JSON.stringify({ Encrypt: encrypt, ToUserName: appid })
      : `<xml><Encrypt><![CDATA[${encrypt}]]></Encrypt><ToUserName><![CDATA[${appid}]]></ToUserName></xml>`;
  return { encrypt, signature, body };
}

/**
 * @param {{token:string, encodingAESKey:string, appid?:string}} config
 * @returns {{ok:boolean, checks:Array<{label:string,ok:boolean,detail?:string}>}}
 */
function runPushSelfTest({ token, encodingAESKey, appid = "wxself-test" }) {
  const checks = [];
  const push = (label, ok, detail) => checks.push({ label, ok: Boolean(ok), detail });

  // 1) 参数格式
  push(
    "WECHAT_MSG_TOKEN 长度 3~32（微信后台要求）",
    typeof token === "string" && token.length >= 3 && token.length <= 32,
    `当前长度 ${token ? token.length : 0}`
  );
  push(
    "WECHAT_MSG_TOKEN 仅含字母或数字",
    typeof token === "string" && /^[A-Za-z0-9]+$/.test(token),
    "微信后台不接受其他字符"
  );
  push(
    "WECHAT_ENCODING_AES_KEY 为 43 位",
    typeof encodingAESKey === "string" && encodingAESKey.length === 43,
    `当前长度 ${encodingAESKey ? encodingAESKey.length : 0}`
  );
  // 这一条是真实踩过的坑：AES 密钥本身是 base64（合法字符含 + 和 /），但微信后台
  // 的表单只接受字母数字。带 + 或 / 时后台**静默拒绝提交**，只回一句「系统繁忙」，
  // 且长度仍显示 43/43 —— 从界面上完全看不出问题在哪，排查代价极高。
  push(
    "WECHAT_ENCODING_AES_KEY 仅含字母或数字（不含 + /）",
    typeof encodingAESKey === "string" && /^[A-Za-z0-9]+$/.test(encodingAESKey),
    (() => {
      if (typeof encodingAESKey !== "string") return "值不是字符串";
      const bad = encodingAESKey.replace(/[A-Za-z0-9]/g, "");
      if (!bad) return "通过";
      return `含非法字符 ${JSON.stringify(bad)}，微信后台会静默拒绝提交（提示「系统繁忙」）`;
    })()
  );
  if (checks.some((c) => !c.ok)) return { ok: false, checks };

  const timestamp = "1700000000";
  const nonce = "self-test-nonce";
  const avatarTraceId = "self-test-trace-avatar";

  // 2) 四种情形都要能正确解析：JSON/XML × 违规/通过
  // 注意：场景名用 `name`、媒体送审标签用 `mediaLabel`，两者不能都叫 label——
  // 同名会静默覆盖，检查项文案会变成「100：...」，两个 pass 场景再也分不清。
  const scenarios = [
    { name: "JSON + 审核通过", wrap: "json", suggest: "pass", mediaLabel: 100, xmlish: false },
    { name: "JSON + 审核违规", wrap: "json", suggest: "risky", mediaLabel: 20002, xmlish: false },
    { name: "XML + 审核通过", wrap: "xml", suggest: "pass", mediaLabel: 100, xmlish: true },
    { name: "XML + 审核违规", wrap: "xml", suggest: "risky", mediaLabel: 20006, xmlish: true }
  ];

  for (const s of scenarios) {
    const message = s.xmlish
      ? `<xml><ToUserName><![CDATA[gh_test]]></ToUserName><Event><![CDATA[wxa_media_check]]></Event>` +
        `<appid><![CDATA[${appid}]]></appid><trace_id><![CDATA[${avatarTraceId}]]></trace_id>` +
        `<version>2</version><errcode>0</errcode>` +
        `<result><suggest>${s.suggest}</suggest><label>${s.mediaLabel}</label></result></xml>`
      : JSON.stringify({
          ToUserName: "gh_test",
          Event: "wxa_media_check",
          appid,
          trace_id: avatarTraceId,
          version: 2,
          errcode: 0,
          result: { suggest: s.suggest, label: s.mediaLabel }
        });

    const built = buildEncryptedCallback({
      token,
      encodingAESKey,
      appid,
      message,
      timestamp,
      nonce,
      wrap: s.wrap
    });

    // 微信实际发来的报文形态（{Encrypt} 或 <Encrypt>），确认提取逻辑正确
    const extracted = extractEncrypt(built.body);
    if (!extracted) {
      push(`${s.name}：能从报文中取出密文`, false, built.body.slice(0, 60));
      continue;
    }
    push(`${s.name}：能从报文中取出密文`, true);

    // 验签：这条是本模块的核心防线，签名不对必须拒绝处理
    const recomputed = computeMsgSignature({ token, timestamp, nonce, encrypt: extracted });
    if (recomputed !== built.signature) {
      push(`${s.name}：msg_signature 校验`, false, `期望 ${built.signature} 实际 ${recomputed}`);
      continue;
    }
    push(`${s.name}：msg_signature 校验`, true);

    let normalized;
    try {
      const decrypted = decryptWechatPayload(extracted, encodingAESKey);
      normalized = normalizeMessage(decrypted.message);
    } catch (e) {
      push(`${s.name}：解密`, false, e.message);
      continue;
    }

    push(
      `${s.name}：trace_id 与 suggest 解析正确`,
      normalized.event === "wxa_media_check" &&
        normalized.traceId === avatarTraceId &&
        normalized.suggest === s.suggest &&
        normalized.label === s.mediaLabel,
      `event=${normalized.event} trace=${normalized.traceId} suggest=${normalized.suggest} label=${normalized.label}`
    );
  }

  // 3) 篡改密文必须导致验签失败（否则等于没有防伪造能力）
  const tampered = buildEncryptedCallback({
    token,
    encodingAESKey,
    appid,
    message: JSON.stringify({ Event: "wxa_media_check", trace_id: "x", errcode: 0, result: { suggest: "pass", label: 100 } }),
    timestamp,
    nonce,
    wrap: "json"
  });
  const tamperedBuf = Buffer.from(tampered.encrypt, "base64");
  tamperedBuf[3] ^= 0xff;
  const tamperedEncrypt = tamperedBuf.toString("base64");
  push(
    "篡改密文会导致 msg_signature 不匹配（防伪造审核通过）",
    computeMsgSignature({ token, timestamp, nonce, encrypt: tamperedEncrypt }) !== tampered.signature
  );

  // 4) extractEncrypt 只认密文，绝不能把明文报文体误判成密文
  //    （「明文报文要不要处理」由 routes/wechatCallback.js 决定：没有 Encrypt 时
  //     必须验 signature，验不过才拒 —— 行为级验证见 scripts/verify-wechat-callback.js）
  push(
    "明文报文体不会被 extractEncrypt 误判为密文",
    extractEncrypt(JSON.stringify({ Event: "wxa_media_check", result: { suggest: "pass" } })) === null &&
      extractEncrypt('<xml><Event><![CDATA[wxa_media_check]]></Event></xml>') === null
  );

  return { ok: checks.every((c) => c.ok), checks };
}

module.exports = { runPushSelfTest, encryptLikeWechat, buildEncryptedCallback };
