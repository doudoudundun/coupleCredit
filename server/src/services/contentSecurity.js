/**
 * 内容安全服务：文本与图片的 UGC 审核。
 *
 * 这是小程序「用户生成内容」合规声明的落地实现。本项目实际的 UGC 面只有两处：
 *   1) 文本 —— 注册时的 username、可修改的 nickname（scene=1 资料）
 *   2) 图片 —— 用户头像
 * 账单的分类/归属/日期都是 picker 点选，不属于 UGC，不走这里。
 *
 * ── 失败策略（本模块最重要的设计决定） ───────────────────────────────
 * 审核服务不可用时怎么办，两个方向都有代价：
 *   fail-closed（一律拒绝）：微信抖动或用户未授权 openid 时，全部用户无法
 *     改昵称、换头像 —— 把一个第三方依赖的故障放大成全站功能不可用。
 *   fail-open（一律放行）：审核形同虚设。
 *
 * 因此按「结论是否明确」分情况：
 *   · 明确 risky / review  → 拒绝（fail-closed）。这是审核的意义所在。
 *   · 明确 pass            → 通过。
 *   · 拿不到结论（网络故障 / 无 openid / 未配置 / 61010 用户超时）
 *                          → 放行并标记 degraded=true，同时留痕。
 *                            既不因第三方故障阻断用户，也不假装审核过了。
 *
 * ── openid 的来源 ──────────────────────────────────────────────
 * 微信要求 msg_sec_check / media_check_async 必须传用户 openid，且该用户
 * 「近两小时访问过小程序」。只有走过微信登录的用户才有 wechat_openid，
 * 密码注册的用户没有。因此支持调用方传 wx.login 的 code 现场换取 openid
 * （小程序里 wx.login 是静默的，用户无感），换取结果不落库。
 */

const { getWechatOpenidByCode } = require("../utils/wechatSession");
const { TOKEN_INVALID_ERRCODES } = require("../utils/wechatAccessToken");

const SCENE = { PROFILE: 1, COMMENT: 2, FORUM: 3, SOCIAL_LOG: 4 };
const MEDIA_TYPE = { AUDIO: 1, IMAGE: 2 };
const VALID_SUGGESTS = new Set(["pass", "risky", "review"]);

const SUGGEST = { PASS: "pass", RISKY: "risky", REVIEW: "review" };

// 微信返回的 raw 可能较大，落库截断（排障够用，且减少敏感数据留存）
const RAW_RESPONSE_MAX = 2000;
const REQUEST_TIMEOUT_MS = 8000;

/** 用户名/昵称允许的字符集：中英文、数字、下划线、连字符、点、空格 */
const TEXT_ALLOWED_PATTERN = /^[\u4e00-\u9fa5\u3040-\u30ffA-Za-z0-9_\-.\s]+$/;
/** 明显的联系方式/导流特征：QQ 号、微信号、手机号、URL */
const TEXT_PROMO_PATTERNS = [
  { re: /https?:\/\/|www\.[A-Za-z0-9-]+\.[A-Za-z]{2,}/i, reason: "URL_NOT_ALLOWED" },
  { re: /(微信|威信|vx|weixin|wechat)\s*[:：]?\s*[A-Za-z0-9_-]{5,}/i, reason: "CONTACT_NOT_ALLOWED" },
  { re: /[Qqｑ]{2}\s*[:：]?\s*\d{5,}/, reason: "CONTACT_NOT_ALLOWED" },
  { re: /(?:\+?86[- ]?)?1[3-9]\d{9}/, reason: "CONTACT_NOT_ALLOWED" }
];

/**
 * 本地兜底校验：不依赖微信，任何情况下都会先跑一遍。
 *
 * 它不替代微信审核（词库远不如微信的模型），
 * 价值在于两点：挡掉明显的导流/联系方式滥用，以及审核服务不可用时的最低保护。
 */
function localTextGuard(rawText, { minLength = 1, maxLength = 50 } = {}) {
  const text = String(rawText == null ? "" : rawText).trim();
  if (!text) return { ok: false, reason: "EMPTY" };
  if (text.length < minLength) return { ok: false, reason: "TOO_SHORT" };
  if (text.length > maxLength) return { ok: false, reason: "TOO_LONG" };
  // 控制字符和零宽字符常用于绕过审核，直接拒绝
  if (/[\u0000-\u001f\u007f\u200b-\u200f\u2028\u2029\ufeff]/.test(text)) {
    return { ok: false, reason: "ILLEGAL_CHARSET" };
  }
  // 顺序很重要：先查「导流/联系方式」这类具体特征，再查字符集。
  // 网址里必然含 ":" 和 "/"，若先跑字符集检查，reason 会一律变成 ILLEGAL_CHARSET，
  // 客户端只能提示「仅支持中英文数字与常用符号」，用户根本不知道真正踩了哪一条。
  for (const { re, reason } of TEXT_PROMO_PATTERNS) {
    if (re.test(text)) return { ok: false, reason };
  }
  if (!TEXT_ALLOWED_PATTERN.test(text)) return { ok: false, reason: "ILLEGAL_CHARSET" };
  return { ok: true, reason: null };
}

const LOCAL_REASON_MESSAGES = {
  EMPTY: "内容不能为空",
  TOO_SHORT: "内容太短",
  TOO_LONG: "内容过长",
  ILLEGAL_CHARSET: "仅支持中英文、数字与常用符号",
  URL_NOT_ALLOWED: "不能包含网址",
  CONTACT_NOT_ALLOWED: "不能包含联系方式"
};

function localReasonMessage(reason) {
  return LOCAL_REASON_MESSAGES[reason] || "内容不符合规范";
}

function createContentSecurityService({ pool, config, accessTokenProvider, fetchImpl }) {
  const doFetch = fetchImpl || fetch;

  function configured() {
    return Boolean(config.wechatAppId && config.wechatSecret);
  }

  function baseUrl() {
    return String(config.publicBaseUrl || "").replace(/\/+$/, "");
  }

  /** 调微信内容安全接口，token 失效时自动刷新重试一次 */
  async function callSecurityApi(apiPath, body) {
    let accessToken = await accessTokenProvider.get();
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
      let payload;
      try {
        const resp = await doFetch(`${apiPath.url}?access_token=${encodeURIComponent(accessToken)}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
          signal: controller.signal
        });
        payload = await resp.json();
      } catch (e) {
        throw new Error(e && e.name === "AbortError" ? "微信内容安全接口超时" : "无法连接微信内容安全接口");
      } finally {
        clearTimeout(timer);
      }

      const errcode = Number(payload && payload.errcode);
      if (errcode === 0) return payload;

      // access_token 失效 → 刷新后重试一次（只重试这一种，其余错误直接抛出）
      if (TOKEN_INVALID_ERRCODES.has(errcode) && attempt === 0) {
        accessToken = await accessTokenProvider.get({ forceRefresh: true });
        continue;
      }
      const err = new Error(`微信内容安全接口返回 errcode ${errcode}`);
      err.wechatErrcode = errcode;
      throw err;
    }
    throw new Error("微信内容安全接口重试后仍失败");
  }

  /**
   * 解析用于审核的 openid：优先库里的 wechat_openid，其次用调用方传来的 code 现换。
   * 现换的 openid 不落库 —— users.wechat_openid 的语义是「该账号已绑定此微信」，
   * 不能被一次审核请求污染。
   */
  async function resolveOpenid({ userId, code }) {
    if (userId) {
      const [rows] = await pool.execute(
        "SELECT wechat_openid FROM users WHERE id = ? LIMIT 1",
        [userId]
      );
      if (rows.length > 0 && rows[0].wechat_openid) return rows[0].wechat_openid;
    }
    if (code) {
      try {
        return await getWechatOpenidByCode(code, config, doFetch);
      } catch (_e) {
        return null;
      }
    }
    return null;
  }

  /**
   * 文本审核。
   * @returns {Promise<{pass:boolean, suggest:string|null, label:number|null,
   *                    degraded:boolean, reason:string|null, source:string, traceId:string|null}>}
   */
  async function checkText({ userId, content, scene = SCENE.PROFILE, code, limits } = {}) {
    const text = String(content == null ? "" : content).trim();
    if (!text) {
      return { pass: false, suggest: null, label: null, degraded: false, reason: "EMPTY", source: "local", traceId: null };
    }

    // 第一道：本地兜底，任何情况下都先跑
    const local = localTextGuard(text, limits);
    if (!local.ok) {
      return {
        pass: false,
        suggest: SUGGEST.RISKY,
        label: null,
        degraded: false,
        reason: local.reason,
        message: localReasonMessage(local.reason),
        source: "local",
        traceId: null
      };
    }

    if (!configured()) {
      return {
        pass: true, suggest: null, label: null, degraded: true,
        reason: "WECHAT_NOT_CONFIGURED", source: "degraded", traceId: null
      };
    }

    const openid = await resolveOpenid({ userId, code });
    if (!openid) {
      return {
        pass: true, suggest: null, label: null, degraded: true,
        reason: "NO_WECHAT_OPENID", source: "degraded", traceId: null
      };
    }

    let payload;
    try {
      payload = await callSecurityApi(
        { url: "https://api.weixin.qq.com/wxa/msg_sec_check" },
        { content: text, version: 2, scene, openid }
      );
    } catch (e) {
      // 有 errcode 说明是业务错误（如 61010 用户超时、45009 超配额），
      // 记具体码；无则可能是网络故障。两种都降级放行。
      return {
        pass: true, suggest: null, label: null, degraded: true,
        reason: e.wechatErrcode ? `WECHAT_ERRCODE_${e.wechatErrcode}` : "WECHAT_UNREACHABLE",
        source: "degraded", traceId: null
      };
    }

    const suggest = payload.result && payload.result.suggest;
    if (!VALID_SUGGESTS.has(suggest)) {
      // 返回体结构不符合预期，不能据此判违规（否则会误伤正常用户）
      return {
        pass: true, suggest: null, label: null, degraded: true,
        reason: "UNEXPECTED_RESPONSE", source: "degraded", traceId: payload.trace_id || null
      };
    }

    const label = payload.result.label == null ? null : Number(payload.result.label);
    return {
      pass: suggest === SUGGEST.PASS,
      suggest,
      label,
      degraded: false,
      reason: suggest === SUGGEST.PASS ? null : `WECHAT_${String(suggest).toUpperCase()}`,
      message: suggest === SUGGEST.PASS ? null : "内容未通过安全审核，请修改后重试",
      source: "wechat",
      traceId: payload.trace_id || null
    };
  }

  /**
   * 发起图片异步审核。
   * 微信会在 30 分钟内通过消息推送把结果送到 /api/wechat/callback。
   *
   * @returns {Promise<{traceId:string|null, degraded:boolean, reason:string|null}>}
   *   degraded=true 表示没能真正发起审核，调用方应自行决定是否放行（见 upload.js）。
   */
  async function startMediaCheck({ userId, mediaUrl, bizType, bizRef, scene = SCENE.PROFILE, code } = {}) {
    if (!configured()) {
      return { traceId: null, degraded: true, reason: "WECHAT_NOT_CONFIGURED" };
    }
    if (!baseUrl()) {
      // media_url 必须能被微信的检测服务器下载，没有公网基址就无从审核
      return { traceId: null, degraded: true, reason: "NO_PUBLIC_BASE_URL" };
    }

    const openid = await resolveOpenid({ userId, code });
    if (!openid) {
      return { traceId: null, degraded: true, reason: "NO_WECHAT_OPENID" };
    }

    const absoluteUrl = /^https?:\/\//i.test(mediaUrl) ? mediaUrl : `${baseUrl()}${mediaUrl}`;

    let payload;
    try {
      payload = await callSecurityApi(
        { url: "https://api.weixin.qq.com/wxa/media_check_async" },
        { media_url: absoluteUrl, media_type: MEDIA_TYPE.IMAGE, version: 2, scene, openid }
      );
    } catch (e) {
      return {
        traceId: null,
        degraded: true,
        reason: e.wechatErrcode ? `WECHAT_ERRCODE_${e.wechatErrcode}` : "WECHAT_UNREACHABLE"
      };
    }

    const traceId = payload.trace_id;
    if (!traceId) return { traceId: null, degraded: true, reason: "NO_TRACE_ID" };

    // 关联 trace_id ↔ 业务：回调只带 trace_id，不带任何业务标识。
    // 这一步失败不抛错：审核已在微信侧发起，只是本地没有关联记录。
    // 让调用方按「无法审核」降级放行，好过让整个换头像请求失败。
    try {
      await pool.execute(
        `INSERT INTO content_checks (trace_id, user_id, biz_type, biz_ref, status)
         VALUES (?, ?, ?, ?, 'pending')`,
        [traceId, userId, bizType, bizRef || null]
      );
    } catch (e) {
      console.error("[content-security] 记录审核任务失败（迁移 028 是否已执行？）:", e.message);
      return { traceId: null, degraded: true, reason: "TRACE_RECORD_FAILED" };
    }

    return { traceId, degraded: false, reason: null };
  }

  /**
   * 处理消息推送回来的审核结果。
   *
   * 关键：只有当下 users.avatar 仍等于本次审核的 biz_ref 时才更新状态。
   * 否则会出现这种情况：用户传了图 A（触发审核）→ 又换成图 B → 图 A 的
   * 违规回调姗姗来迟 → 把图 B 也标成违规。用 biz_ref 比对可以避免。
   *
   * @returns {Promise<{handled:boolean, reason:string|null, userId:number|null}>}
   */
  async function applyMediaCheckResult({ traceId, errcode, suggest, label, raw, pool: queryPool } = {}) {
    const db = queryPool || pool;
    if (!traceId) return { handled: false, reason: "NO_TRACE_ID", userId: null };

    const [rows] = await db.execute(
      "SELECT id, user_id, biz_type, biz_ref, status FROM content_checks WHERE trace_id = ? LIMIT 1",
      [traceId]
    );
    if (rows.length === 0) {
      // 不是本服务发起的任务（可能是别的 appid 或历史数据），不处理
      return { handled: false, reason: "UNKNOWN_TRACE_ID", userId: null };
    }

    const record = rows[0];
    if (record.status !== "pending") {
      // 微信可能重复推送，幂等处理
      return { handled: false, reason: "ALREADY_RESOLVED", userId: record.user_id };
    }

    const rawText = raw == null ? null : String(raw).slice(0, RAW_RESPONSE_MAX);
    const numericErrcode = errcode == null ? null : Number(errcode);

    // errcode 非 0 表示这次检测本身失败（-1008 是图片下载失败），
    // 拿不到结论，标 failed 而不是 rejected —— 不能把「没审成」当成「违规」。
    if (numericErrcode !== null && numericErrcode !== 0) {
      await db.execute(
        `UPDATE content_checks SET status = 'failed', raw_response = ?, resolved_at = NOW() WHERE id = ?`,
        [rawText, record.id]
      );
      return { handled: true, reason: `CHECK_FAILED_${numericErrcode}`, userId: record.user_id };
    }

    const valid = VALID_SUGGESTS.has(suggest);
    const approved = valid && suggest === SUGGEST.PASS;
    const nextStatus = approved ? "approved" : "rejected";

    await db.execute(
      `UPDATE content_checks SET status = ?, suggest = ?, label = ?, raw_response = ?, resolved_at = NOW()
       WHERE id = ?`,
      [nextStatus, valid ? suggest : null, label == null ? null : Number(label), rawText, record.id]
    );

    if (record.biz_type === "avatar") {
      // 仅当用户当前头像仍是本次送审的那张时才改状态
      const [result] = await db.execute(
        `UPDATE users SET avatar_status = ?
         WHERE id = ? AND avatar = ? AND avatar_status = 'pending'`,
        [approved ? "approved" : "rejected", record.user_id, record.biz_ref]
      );
      if (result.affectedRows === 0) {
        return { handled: true, reason: "AVATAR_CHANGED_SKIP", userId: record.user_id };
      }
    }

    return { handled: true, reason: approved ? "APPROVED" : "REJECTED", userId: record.user_id };
  }

  return {
    configured,
    resolveOpenid,
    checkText,
    startMediaCheck,
    applyMediaCheckResult,
    localTextGuard,
    baseUrl
  };
}

module.exports = {
  createContentSecurityService,
  localTextGuard,
  localReasonMessage,
  SCENE,
  MEDIA_TYPE,
  SUGGEST,
  VALID_SUGGESTS,
  TEXT_ALLOWED_PATTERN,
  TEXT_PROMO_PATTERNS,
  LOCAL_REASON_MESSAGES
};
