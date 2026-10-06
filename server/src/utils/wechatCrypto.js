/**
 * 微信「消息推送」安全模式的验签与解密。
 *
 * 用途：media_check_async（图片内容安全）的审核结果只通过消息推送回调下发，
 * 回调报文是加密的，必须验签 + 解密才能拿到 trace_id 与 result.suggest。
 *
 * 关于明文模式：
 *   明文字段里直接带着 suggest，若「没有 Encrypt 就直接信」，任何人都能构造
 *   一份 "suggest: pass" 的假回调，把违规图片洗成已通过。所以本模块只负责
 *   「取密文 / 验签 / 解密」这几件机械的事，是否接收明文由
 *   routes/wechatCallback.js 决定：报文里没有 Encrypt 时，必须先通过
 *   verifyPlainSignature（同样需要 Token）才处理，验不过一律 401。
 *   这样后台选明文/兼容/安全三种模式都能收到结果，又不会被伪造报文洗白。
 *
 * 环境无关、无外部依赖：只依赖 node:crypto，便于门禁脚本直接 require 做断言。
 *
 * 算法（与微信官方 PHP/Java 示例一致）：
 *   签名   msg_signature = sha1(sort([token, timestamp, nonce, encrypt]).join(''))
 *   密钥   AESKey = base64_decode(EncodingAESKey + '=')          → 32 字节
 *   IV     AESKey 前 16 字节
 *   明文   random(16) | msgLen(4, network order) | msg | appid
 *   填充   PKCS#7 变体，**块大小为 32 字节**（不是标准 AES 的 16）
 *
 * 关于填充：正因为块大小是 32，绝不能交给 Node 自动去填充——
 * 当填充值为 17~32 时 Node 会抛 "bad decrypt"。必须 setAutoPadding(false) 后手工剥离。
 */

const crypto = require("crypto");

const ENCODING_AES_KEY_LENGTH = 43; // 微信后台生成的 EncodingAESKey 固定 43 位
const AES_BLOCK_SIZE = 32; // 微信的填充块大小，注意不是 16
const RANDOM_PREFIX_LENGTH = 16; // 明文头部 16 字节随机数
const LENGTH_FIELD_LENGTH = 4; // 随后 4 字节网络序的消息长度

/**
 * 计算消息签名。
 * @returns {string} 40 位十六进制小写 sha1
 */
function computeMsgSignature({ token, timestamp, nonce, encrypt }) {
  const parts = [token, timestamp, nonce, encrypt].map((v) => String(v == null ? "" : v));
  return crypto.createHash("sha1").update(parts.sort().join(""), "utf8").digest("hex");
}

/**
 * 定长时间比较，避免通过响应时间侧信道逐字节猜测签名。
 */
function timingSafeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const bufA = Buffer.from(a, "utf8");
  const bufB = Buffer.from(b, "utf8");
  if (bufA.length !== bufB.length || bufA.length === 0) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * 校验签名。签名不合法时必须直接拒绝，不能继续解密——否则解密错误信息
 * 会变成攻击者可用的 oracle。
 */
function verifyMsgSignature({ token, timestamp, nonce, encrypt, signature }) {
  return timingSafeEqualHex(computeMsgSignature({ token, timestamp, nonce, encrypt }), signature);
}

/** EncodingAESKey（43 位）→ 32 字节 AES 密钥 */
function decodeAesKey(encodingAESKey) {
  if (typeof encodingAESKey !== "string" || encodingAESKey.length !== ENCODING_AES_KEY_LENGTH) {
    throw new Error(
      `EncodingAESKey 必须是 ${ENCODING_AES_KEY_LENGTH} 位字符（当前 ${encodingAESKey ? encodingAESKey.length : 0}）`
    );
  }
  const key = Buffer.from(encodingAESKey + "=", "base64");
  if (key.length !== 32) {
    throw new Error(`EncodingAESKey 解码后必须是 32 字节（当前 ${key.length}）`);
  }
  return key;
}

/**
 * 解密微信密文。
 * @returns {{ message: string, appid: string }}
 */
function decryptWechatPayload(encryptBase64, encodingAESKey) {
  const key = decodeAesKey(encodingAESKey);
  const iv = key.subarray(0, 16);

  let encrypted;
  try {
    encrypted = Buffer.from(String(encryptBase64), "base64");
  } catch (_e) {
    throw new Error("密文不是合法的 base64");
  }
  if (encrypted.length === 0 || encrypted.length % 16 !== 0) {
    throw new Error("密文长度非法");
  }

  const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
  // 关键：微信块大小是 32，交给 Node 自动去填充会在填充值 > 16 时报 bad decrypt
  decipher.setAutoPadding(false);

  let decrypted;
  try {
    decrypted = Buffer.concat([decipher.update(encrypted), decipher.final()]);
  } catch (_e) {
    throw new Error("解密失败（密钥不匹配或密文被篡改）");
  }

  if (decrypted.length < RANDOM_PREFIX_LENGTH + LENGTH_FIELD_LENGTH) {
    throw new Error("解密结果过短");
  }

  const padLength = decrypted[decrypted.length - 1];
  if (padLength < 1 || padLength > AES_BLOCK_SIZE) {
    throw new Error(`填充值非法（${padLength}）`);
  }
  const content = decrypted.subarray(0, decrypted.length - padLength);
  if (content.length < RANDOM_PREFIX_LENGTH + LENGTH_FIELD_LENGTH) {
    throw new Error("剥离填充后内容过短");
  }

  const messageLength = content.readUInt32BE(RANDOM_PREFIX_LENGTH);
  const messageStart = RANDOM_PREFIX_LENGTH + LENGTH_FIELD_LENGTH;
  if (messageStart + messageLength > content.length) {
    throw new Error("消息长度越界");
  }

  return {
    message: content.subarray(messageStart, messageStart + messageLength).toString("utf8"),
    appid: content.subarray(messageStart + messageLength).toString("utf8")
  };
}

/** 取 XML 标签文本，兼容 <Tag><![CDATA[v]]></Tag> 与 <Tag>v</Tag> 两种写法 */
function pickXmlTag(xml, tag) {
  const cdata = new RegExp(`<${tag}>\\s*<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>\\s*</${tag}>`, "i");
  const cdataMatch = cdata.exec(xml);
  if (cdataMatch) return cdataMatch[1];

  const plain = new RegExp(`<${tag}>\\s*([\\s\\S]*?)\\s*</${tag}>`, "i");
  const plainMatch = plain.exec(xml);
  return plainMatch ? plainMatch[1] : null;
}

/**
 * 从回调原始 body 中取出密文。
 * 后台的「数据格式」可配 XML 或 JSON，两种都要支持；
 * 但不接受任何未加密的报文（见文件头说明）。
 * @returns {string|null}
 */
function extractEncrypt(rawBody) {
  if (typeof rawBody !== "string" && !Buffer.isBuffer(rawBody)) return null;
  const text = Buffer.isBuffer(rawBody) ? rawBody.toString("utf8") : rawBody;
  const trimmed = text.trim();
  if (!trimmed) return null;

  if (trimmed.startsWith("{")) {
    try {
      const parsed = JSON.parse(trimmed);
      return typeof parsed.Encrypt === "string" ? parsed.Encrypt : null;
    } catch (_e) {
      return null;
    }
  }
  const encrypt = pickXmlTag(trimmed, "Encrypt");
  return encrypt || null;
}

/**
 * 把解密后的消息归一化为一个固定结构。
 * 明文可能是 XML 也可能是 JSON，取决于后台数据格式，这里统一成对象。
 *
 * @returns {{ event: string|null, appid: string|null, traceId: string|null,
 *             errcode: number|null, suggest: string|null, label: number|null }}
 */
function normalizeMessage(message) {
  const text = String(message || "").trim();
  const result = { event: null, appid: null, traceId: null, errcode: null, suggest: null, label: null };
  if (!text) return result;

  let flat = null;
  if (text.startsWith("{")) {
    try {
      flat = JSON.parse(text);
    } catch (_e) {
      flat = null;
    }
  }

  const read = (jsonKey, xmlTag) => {
    if (flat) return flat[jsonKey];
    return pickXmlTag(text, xmlTag);
  };

  result.event = read("Event", "Event");
  result.appid = read("appid", "appid");
  result.traceId = read("trace_id", "trace_id");

  const rawErrcode = read("errcode", "errcode");
  result.errcode = rawErrcode == null || rawErrcode === "" ? null : Number(rawErrcode);

  if (flat && flat.result) {
    result.suggest = flat.result.suggest || null;
    result.label = flat.result.label == null ? null : Number(flat.result.label);
  } else {
    // XML：<result> 是一层嵌套，单独切出这一小段再取子标签，避免误取到 <detail> 里的同名字段
    const resultBlock = /<(?:result)>([\s\S]*?)<\/(?:result)>/i.exec(text);
    if (resultBlock) {
      result.suggest = pickXmlTag(resultBlock[1], "suggest");
      const rawLabel = pickXmlTag(resultBlock[1], "label");
      result.label = rawLabel == null ? null : Number(rawLabel);
    }
  }

  return result;
}

/**
 * 明文模式（未加密）的签名：sha1(sort([token, timestamp, nonce]).join(''))。
 * 与 computeMsgSignature 的区别是**不包含 encrypt**，排序集合不同，结果也不同。
 *
 * 两个使用场景：
 *   1) 明文模式的 URL 校验（后台点保存时的 GET）；
 *   2) 明文模式/兼容模式推送过来的 POST 报文 —— 报文体里没有 Encrypt 可验，
 *      只能靠这个签名证明请求来自微信。
 */
function computePlainSignature({ token, timestamp, nonce }) {
  const parts = [token, timestamp, nonce].map((v) => String(v == null ? "" : v));
  return crypto.createHash("sha1").update(parts.sort().join(""), "utf8").digest("hex");
}

/** 校验明文签名。不合法时必须直接拒绝，否则明文字段（含 suggest）可被任意伪造。 */
function verifyPlainSignature({ token, timestamp, nonce, signature }) {
  return timingSafeEqualHex(computePlainSignature({ token, timestamp, nonce }), signature);
}

module.exports = {
  computeMsgSignature,
  computePlainSignature,
  verifyMsgSignature,
  verifyPlainSignature,
  decodeAesKey,
  decryptWechatPayload,
  pickXmlTag,
  extractEncrypt,
  normalizeMessage,
  ENCODING_AES_KEY_LENGTH,
  AES_BLOCK_SIZE
};
