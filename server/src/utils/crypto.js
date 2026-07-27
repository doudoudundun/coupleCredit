/**
 * 字段级 AES-256-GCM 加解密工具（零依赖，基于 Node 内置 crypto）。
 *
 * 用途：账号保险箱模块对敏感字段（密码/安全问题/答案）做透明加解密。
 *
 * 密文存储格式："base64(iv):base64(ciphertext+authTag)"
 *   - iv：12 字节随机初始化向量（每次加密重新生成）
 *   - authTag：16 字节 GCM 认证标签，附在密文末尾，解密时校验完整性
 *
 * 密钥来源：环境变量 ENCRYPTION_KEY，base64 编码，解码后必须为 32 字节（AES-256）。
 * 注意：密钥仅服务端持有，绝不下发给客户端，也不写入日志。
 */
const crypto = require("crypto");

const ALGORITHM = "aes-256-gcm";
const IV_LENGTH = 12;   // GCM 推荐 12 字节 IV
const TAG_LENGTH = 16;  // GCM authTag 固定 16 字节

/**
 * 从 base64 环境变量解码出 32 字节的 AES-256 密钥。
 * @param {string} base64Key
 * @returns {Buffer} 32 字节密钥
 */
function decodeKey(base64Key) {
  if (!base64Key) {
    throw new Error("ENCRYPTION_KEY is not configured");
  }
  const buf = Buffer.from(base64Key, "base64");
  if (buf.length !== 32) {
    throw new Error(
      `ENCRYPTION_KEY must decode to 32 bytes for AES-256 (got ${buf.length} bytes)`
    );
  }
  return buf;
}

/**
 * 加密一段明文。
 * @param {string} plainText
 * @param {Buffer} key 32 字节密钥
 * @returns {string} "base64(iv):base64(ciphertext+authTag)"
 */
function encrypt(plainText, key) {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, key, iv);
  const encrypted = Buffer.concat([
    cipher.update(String(plainText), "utf8"),
    cipher.final()
  ]);
  const tag = cipher.getAuthTag();
  return `${iv.toString("base64")}:${Buffer.concat([encrypted, tag]).toString("base64")}`;
}

/**
 * 解密密文。
 * @param {string} payload "base64(iv):base64(ciphertext+authTag)"
 * @param {Buffer} key 32 字节密钥
 * @returns {string} 明文
 */
function decrypt(payload, key) {
  const colonIndex = payload.indexOf(":");
  if (colonIndex <= 0) {
    throw new Error("Invalid encrypted payload format");
  }
  const iv = Buffer.from(payload.slice(0, colonIndex), "base64");
  const blob = Buffer.from(payload.slice(colonIndex + 1), "base64");
  const ciphertext = blob.subarray(0, blob.length - TAG_LENGTH);
  const tag = blob.subarray(blob.length - TAG_LENGTH);

  const decipher = crypto.createDecipheriv(ALGORITHM, key, iv);
  decipher.setAuthTag(tag);
  const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return decrypted.toString("utf8");
}

/**
 * 把对象序列化为 JSON 后整体加密。
 * @param {object} obj
 * @param {Buffer} key
 * @returns {string} 密文
 */
function encryptJson(obj, key) {
  return encrypt(JSON.stringify(obj), key);
}

/**
 * 解密并还原为对象。
 * @param {string} payload
 * @param {Buffer} key
 * @returns {object}
 */
function decryptJson(payload, key) {
  return JSON.parse(decrypt(payload, key));
}

module.exports = {
  decodeKey,
  encrypt,
  decrypt,
  encryptJson,
  decryptJson
};
