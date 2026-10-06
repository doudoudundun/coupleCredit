const required = ["DB_PASSWORD", "INVITE_CODE", "JWT_SECRET", "ENCRYPTION_KEY"];

function readConfig() {
  for (const key of required) {
    if (!process.env[key]) {
      throw new Error(`Missing required environment variable: ${key}`);
    }
  }

  // JWT_SECRET 长度校验（防止使用过短的弱密钥）
  const jwtSecret = process.env.JWT_SECRET;
  if (jwtSecret.length < 32) {
    throw new Error(`JWT_SECRET must be at least 32 characters (current: ${jwtSecret.length})`);
  }

  // ENCRYPTION_KEY 校验：base64 解码后必须为 32 字节（AES-256）。
  // 生成方式：openssl rand -base64 32
  const encryptionKey = process.env.ENCRYPTION_KEY;
  const encryptionKeyBytes = Buffer.from(encryptionKey, "base64");
  if (encryptionKeyBytes.length !== 32) {
    throw new Error(
      `ENCRYPTION_KEY must decode to 32 bytes for AES-256 (got ${encryptionKeyBytes.length} bytes; generate with "openssl rand -base64 32")`
    );
  }

  // CORS 允许的来源白名单（逗号分隔）。移动端 App 无 Origin，不受 CORS 限制，
  // 此项主要用于锁死 Web/H5 端。默认只允许本机，生产环境通过环境变量覆盖。
  const allowedOrigins = (process.env.ALLOWED_ORIGINS || "http://localhost")
    .split(",")
    .map(s => s.trim())
    .filter(Boolean);

  return {
    host: process.env.HOST || "0.0.0.0",
    port: Number(process.env.PORT || 8080),
    dbHost: process.env.DB_HOST || "127.0.0.1",
    dbPort: Number(process.env.DB_PORT || 3306),
    dbName: process.env.DB_NAME || "couple_credit_private",
    dbUser: process.env.DB_USER || "couple_app",
    dbPassword: process.env.DB_PASSWORD,
    inviteCode: process.env.INVITE_CODE,
    bcryptRounds: Number(process.env.BCRYPT_ROUNDS || 10),
    jwtSecret,
    encryptionKey, // base64 字符串，使用时再解码（crypto.decodeKey）
    allowedOrigins,
    // 微信小程序登录配置（可选，未配置则微信登录接口返回未启用）
    wechatAppId: process.env.WECHAT_APPID || "",
    wechatSecret: process.env.WECHAT_SECRET || "",
    // 微信「消息推送」安全模式配置（内容安全异步回调必需）：
    //   WECHAT_MSG_TOKEN      后台「消息推送」页填的 Token，用于校验 msg_signature
    //   WECHAT_ENCODING_AES_KEY  同页的 EncodingAESKey（43 位），用于解密回调报文
    wechatMsgToken: process.env.WECHAT_MSG_TOKEN || "",
    wechatEncodingAESKey: process.env.WECHAT_ENCODING_AES_KEY || "",
    // 公网基址：media_check_async 的 media_url 必须能被微信检测服务器下载，
    // 所以要把 /uploads/xxx 拼成绝对地址。未配则跳过图片审核（见 contentSecurity）。
    publicBaseUrl: process.env.PUBLIC_BASE_URL || "https://api.couplecredit.top"
  };
}

module.exports = { readConfig };
