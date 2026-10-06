/**
 * 家务记录（housework）V1 路由 —— spec: docs/HOUSEWORK_RECORDS_SPEC_20261001.md（小程序仓库）。
 *
 * 结构分区：
 *   1. 常量与内置推荐包        6. 分类 / 模板 / 推荐 / 偏好
 *   2. 基础校验 helpers        7. 记录
 *   3. 时间 / 十进制           8. 统计
 *   4. 筛选 / cursor / hash    9. 空间与 bootstrap
 *   5. 授权 / 幂等 / 审计     10. 路由表
 *
 * 关键不变量（spec 第 8/9 节）：
 *   - 身份只来自 req.userId（JWT），query/body 的 userId 不参与鉴权；
 *   - 每个子资源查询同时约束 id + spaceId；
 *   - 所有写操作：授权与空间可写检查先于幂等回放；乐观锁 WHERE version=expectedVersion；
 *     成功写同事务递增 space.revision、追加审计、记录 mutation receipt；
 *   - 关闭空间一切写返回 409 SPACE_CLOSED；冻结不依赖任何 TTL 缓存。
 */
const express = require("express");
const crypto = require("crypto");

const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const {
  uuid,
  normalizeName,
  pickDisplayName,
  loadDisplayNames,
  ensurePersonalSpace,
  ensureSharedSpaceForBootstrap,
  ensureFallbackCategory
} = require("../utils/houseworkLifecycle");

const TIMEZONE = "Asia/Shanghai";
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;
const DATE_MIN = "2000-01-01";
const MAX_RANGE_DAYS = 366;
const MAX_ACTIVE_CATEGORIES = 50;
const MAX_ACTIVE_TEMPLATES = 200;
const MAX_PINNED = 12;
const MAX_ENABLED_FIELDS = 8;
const MAX_OPTIONS = 20;
const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 50;
const MAX_IDS_PER_FILTER = 50;
const MAX_Q_LENGTH = 50;
const MAX_PRESET_KEYS = 200;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const HM_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const COLOR_RE = /^#[0-9a-fA-F]{6}$/;
const DEC2_RE = /^\d{1,5}(\.\d{1,2})?$/;
const DEC2_SIGNED_RE = /^-?\d{1,5}(\.\d{1,2})?$/;

const OPERATIONS = Object.freeze({
  CATEGORIES_CREATE: "categories.create",
  CATEGORIES_UPDATE: "categories.update",
  CATEGORIES_ORDER: "categories.order",
  TEMPLATES_CREATE: "templates.create",
  TEMPLATES_UPDATE: "templates.update",
  PRESETS_APPLY: "presets.apply",
  SETTINGS_UPDATE: "settings.update",
  PREFERENCES_UPDATE: "preferences.update",
  RECORDS_CREATE: "records.create",
  RECORDS_UPDATE: "records.update",
  RECORDS_DELETE: "records.delete",
  RECORDS_RESTORE: "records.restore"
});

const FIELD_TYPES = ["text", "number", "single_select", "multi_select", "boolean"];
const RECORD_PATCHABLE = new Set([
  "completedDate",
  "completedTime",
  "participants",
  "quantity",
  "durationMinutes",
  "fieldValues",
  "note"
]);
const RECORD_IMMUTABLE_KEYS = [
  "recordId",
  "spaceId",
  "templateId",
  "templateVersion",
  "snapshot",
  "weight",
  "unit",
  "measureMode",
  "categoryId",
  "name",
  "createdBy",
  "version"
];

/* ================================================================== *
 * 1. 常量与内置推荐包（代码常量管理，spec 5.4 / 决策 8）
 *    注册表与基础包定义在 services/houseworkPresets.js（与基础包播撒共用，防两处漂移）
 * ================================================================== */

const { PRESET_PACKS, PRESET_KEY_SET } = require("../services/houseworkPresets");

/* ================================================================== *
 * 2. 基础校验 helpers
 * ================================================================== */

function codePointLength(value) {
  return [...String(value)].length;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isUuid(value) {
  return typeof value === "string" && UUID_RE.test(value);
}

function reject(req, status, code, message, details) {
  return new ApiError(status, code, message, { ...(details || {}), requestId: req.requestId || uuid() });
}

function validationError(req, message, fieldErrors) {
  return reject(req, 422, "VALIDATION_ERROR", message, { fieldErrors });
}

function fieldError(path, code, message) {
  return { path, code, message };
}

function requireMutationId(req, body) {
  const mutationId = body && body.clientMutationId;
  if (mutationId === undefined || mutationId === null || mutationId === "") {
    throw validationError(req, "缺少 clientMutationId", [fieldError("clientMutationId", "REQUIRED", "clientMutationId 必填")]);
  }
  if (typeof mutationId !== "string" || !isUuid(mutationId)) {
    throw validationError(req, "clientMutationId 必须是 UUID", [fieldError("clientMutationId", "INVALID_TYPE", "clientMutationId 必须是 UUID 字符串")]);
  }
  return mutationId;
}

function parseExpectedVersion(req, raw) {
  if (raw === undefined || raw === null || raw === "") {
    throw validationError(req, "缺少 expectedVersion", [fieldError("expectedVersion", "REQUIRED", "expectedVersion 必填")]);
  }
  const value = typeof raw === "number" ? raw : Number(String(raw).trim());
  if (!Number.isSafeInteger(value) || value < 1) {
    throw validationError(req, "expectedVersion 无效", [fieldError("expectedVersion", "INVALID_TYPE", "expectedVersion 必须是正整数")]);
  }
  return value;
}

function parseExpectedRevision(req, raw) {
  if (raw === undefined || raw === null || raw === "") {
    throw validationError(req, "缺少 expectedRevision", [fieldError("expectedRevision", "REQUIRED", "expectedRevision 必填")]);
  }
  const value = typeof raw === "number" ? raw : Number(String(raw).trim());
  if (!Number.isSafeInteger(value) || value < 1) {
    throw validationError(req, "expectedRevision 无效", [fieldError("expectedRevision", "INVALID_TYPE", "expectedRevision 必须是正整数")]);
  }
  return value;
}

function parseLimit(req, raw) {
  if (raw === undefined || raw === null || raw === "") return DEFAULT_LIMIT;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_LIMIT) {
    throw validationError(req, "limit 无效", [fieldError("limit", "OUT_OF_RANGE", `limit 必须是 1–${MAX_LIMIT} 的整数`)]);
  }
  return value;
}

function parseIdList(req, raw, path) {
  if (raw === undefined || raw === null || raw === "") return null;
  if (typeof raw !== "string") {
    throw validationError(req, `${path} 格式错误`, [fieldError(path, "INVALID_TYPE", "ID 列表必须是逗号分隔字符串")]);
  }
  const parts = raw.split(",").map((s) => s.trim()).filter(Boolean);
  if (parts.length === 0) return null;
  if (parts.length > MAX_IDS_PER_FILTER) {
    throw validationError(req, `${path} 数量超限`, [fieldError(path, "OUT_OF_RANGE", `每组最多 ${MAX_IDS_PER_FILTER} 个 ID`)]);
  }
  const ids = parts.map((s) => Number(s));
  if (ids.some((n) => !Number.isSafeInteger(n) || n < 1)) {
    throw validationError(req, `${path} 含非法 ID`, [fieldError(path, "INVALID_TYPE", "ID 必须是正整数")]);
  }
  return [...new Set(ids)];
}

function parseQueryText(req, raw, path) {
  if (raw === undefined || raw === null) return null;
  if (typeof raw !== "string") {
    throw validationError(req, "搜索词格式错误", [fieldError(path, "INVALID_TYPE", "搜索词必须是字符串")]);
  }
  const trimmed = raw.trim();
  if (trimmed === "") return null;
  if (codePointLength(trimmed) > MAX_Q_LENGTH) {
    throw validationError(req, "搜索词过长", [fieldError(path, "OUT_OF_RANGE", `搜索词最多 ${MAX_Q_LENGTH} 字符`)]);
  }
  return trimmed;
}

function likeEscape(value) {
  return value.replace(/[\\%_]/g, (ch) => `\\${ch}`);
}

function parseStatusFilter(req, raw) {
  if (raw === undefined || raw === null || raw === "") return "active";
  if (raw !== "active" && raw !== "archived" && raw !== "all") {
    throw validationError(req, "status 无效", [fieldError("status", "INVALID_OPTION", "status 只能是 active/archived/all")]);
  }
  return raw;
}

function validateColor(req, value, path) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !COLOR_RE.test(value)) {
    throw validationError(req, "颜色格式错误", [fieldError(path, "INVALID_TYPE", "颜色必须是 #RRGGBB")]);
  }
  return value;
}

function isSingleGrapheme(value) {
  if (typeof value !== "string" || value === "") return false;
  if (codePointLength(value) > 8) return false;
  if (typeof Intl !== "undefined" && Intl.Segmenter) {
    const segmenter = new Intl.Segmenter("zh", { granularity: "grapheme" });
    return [...segmenter.segment(value)].length === 1;
  }
  return codePointLength(value) === 1;
}

function validateIcon(req, value, path) {
  if (value === undefined || value === null) return null;
  if (!isPlainObject(value)) {
    throw validationError(req, "图标格式错误", [fieldError(path, "INVALID_TYPE", "图标必须是 {type,value} 对象")]);
  }
  const type = value.type;
  const iconValue = value.value;
  if (type !== "iconKey" && type !== "emoji") {
    throw validationError(req, "图标类型错误", [fieldError(`${path}.type`, "INVALID_OPTION", "图标 type 只能是 iconKey/emoji")]);
  }
  if (typeof iconValue !== "string" || iconValue.trim() === "") {
    throw validationError(req, "图标内容错误", [fieldError(`${path}.value`, "REQUIRED", "图标 value 必填")]);
  }
  if (codePointLength(iconValue) > 40) {
    throw validationError(req, "图标内容过长", [fieldError(`${path}.value`, "OUT_OF_RANGE", "图标 value 最多 40 字符")]);
  }
  if (type === "emoji" && !isSingleGrapheme(iconValue)) {
    throw validationError(req, "emoji 图标必须是单个图形簇", [fieldError(`${path}.value`, "INVALID_TYPE", "emoji 必须是单个图形簇")]);
  }
  return { type, value: iconValue };
}

function parseSortOrder(req, raw, path) {
  if (raw === undefined || raw === null) return null;
  const value = typeof raw === "number" ? raw : Number(String(raw));
  if (!Number.isSafeInteger(value) || value < 0 || value > 999999) {
    throw validationError(req, "排序值无效", [fieldError(path, "OUT_OF_RANGE", "sortOrder 必须是 0–999999 的整数")]);
  }
  return value;
}

/* ================================================================== *
 * 3. 时间 / 十进制（定点数，整数分/BigInt 运算，spec 8.1/8.6）
 * ================================================================== */

function nowContext() {
  const now = new Date();
  const shifted = new Date(now.getTime() + SHANGHAI_OFFSET_MS).toISOString();
  return {
    serverNow: now.toISOString(),
    today: shifted.slice(0, 10),
    nowMinutes: shifted.slice(11, 16),
    timezone: TIMEZONE
  };
}

function isValidDateString(value) {
  if (!DATE_RE.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

function monthRange(today) {
  const [y, m] = today.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { dateFrom: `${today.slice(0, 7)}-01`, dateTo: `${today.slice(0, 7)}-${String(last).padStart(2, "0")}` };
}

function addDays(dateStr, days) {
  const [y, m, d] = dateStr.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

function daysBetween(from, to) {
  const [fy, fm, fd] = from.split("-").map(Number);
  const [ty, tm, td] = to.split("-").map(Number);
  return Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / 86400000);
}

function parseDateRange(req, query, { forWrite = false } = {}) {
  const { today } = nowContext();
  let dateFrom = typeof query.dateFrom === "string" && query.dateFrom ? query.dateFrom : null;
  let dateTo = typeof query.dateTo === "string" && query.dateTo ? query.dateTo : null;
  if (!forWrite && (dateFrom === null || dateTo === null)) {
    const range = monthRange(today);
    if (dateFrom === null) dateFrom = range.dateFrom;
    if (dateTo === null) dateTo = range.dateTo;
  }
  if (dateFrom !== null && !isValidDateString(dateFrom)) {
    throw validationError(req, "dateFrom 无效", [fieldError("dateFrom", "INVALID_TYPE", "dateFrom 必须是 YYYY-MM-DD")]);
  }
  if (dateTo !== null && !isValidDateString(dateTo)) {
    throw validationError(req, "dateTo 无效", [fieldError("dateTo", "INVALID_TYPE", "dateTo 必须是 YYYY-MM-DD")]);
  }
  if (dateFrom !== null && dateTo !== null) {
    if (dateFrom > dateTo) {
      throw validationError(req, "日期范围无效", [fieldError("dateTo", "OUT_OF_RANGE", "dateFrom 不能晚于 dateTo")]);
    }
    if (daysBetween(dateFrom, dateTo) + 1 > MAX_RANGE_DAYS) {
      throw validationError(req, "日期范围过长", [fieldError("dateTo", "OUT_OF_RANGE", `单次范围最长 ${MAX_RANGE_DAYS} 天`)]);
    }
  }
  return { dateFrom, dateTo, today };
}

function parseCompletedDate(req, raw) {
  if (typeof raw !== "string" || !isValidDateString(raw)) {
    throw validationError(req, "完成日期无效", [fieldError("completedDate", "INVALID_TYPE", "completedDate 必须是 YYYY-MM-DD")]);
  }
  const { today } = nowContext();
  if (raw < DATE_MIN || raw > today) {
    throw validationError(req, "完成日期超出范围", [fieldError("completedDate", "OUT_OF_RANGE", `完成日期必须在 ${DATE_MIN} 至今天之间`)]);
  }
  return raw;
}

function parseCompletedTime(req, raw, completedDate) {
  if (raw === undefined || raw === null || raw === "") return null;
  if (typeof raw !== "string" || !HM_RE.test(raw)) {
    throw validationError(req, "完成时间无效", [fieldError("completedTime", "INVALID_TYPE", "completedTime 必须是 HH:mm")]);
  }
  const { today, nowMinutes } = nowContext();
  if (completedDate === today && raw > nowMinutes) {
    throw validationError(req, "完成时间不能晚于当前时间", [fieldError("completedTime", "OUT_OF_RANGE", "当天的完成时间不能晚于服务端当前分钟")]);
  }
  return raw;
}

/** 十进制字符串 → 整数分（cents）。带范围校验，拒绝 NaN/科学记数/超精度。 */
function parseDecimalCents(req, raw, path, { min = null, max = null, signed = false } = {}) {
  if (typeof raw !== "string") {
    throw validationError(req, "数值必须是十进制字符串", [fieldError(path, "INVALID_TYPE", "必须是十进制字符串（最多两位小数）")]);
  }
  const trimmed = raw.trim();
  const re = signed ? DEC2_SIGNED_RE : DEC2_RE;
  if (!re.test(trimmed)) {
    throw validationError(req, "数值格式错误", [fieldError(path, "INVALID_TYPE", "最多两位小数的十进制字符串，不能用科学记数法")]);
  }
  const [intPart, fracPart = ""] = trimmed.split(".");
  const cents = Number(intPart) * 100 + Number((fracPart + "00").slice(0, 2)) * (trimmed.startsWith("-") ? -1 : 1);
  if (!Number.isSafeInteger(cents)) {
    throw validationError(req, "数值超出范围", [fieldError(path, "OUT_OF_RANGE", "数值超出可表示范围")]);
  }
  if (min !== null && cents < min) {
    throw validationError(req, "数值低于下限", [fieldError(path, "OUT_OF_RANGE", `不能小于 ${formatCents(min)}`)]);
  }
  if (max !== null && cents > max) {
    throw validationError(req, "数值高于上限", [fieldError(path, "OUT_OF_RANGE", `不能大于 ${formatCents(max)}`)]);
  }
  return cents;
}

function formatCents(cents) {
  const value = typeof cents === "bigint" ? cents : BigInt(Math.trunc(cents));
  const negative = value < 0n;
  const abs = negative ? -value : value;
  const intPart = abs / 100n;
  const frac = String(abs % 100n).padStart(2, "0");
  return `${negative ? "-" : ""}${intPart}.${frac}`;
}

/** BigInt 定点累加后四舍五入输出（先累计精确值再输出舍入，spec 7）。 */
function divideRoundHalfUp(total, divisor) {
  return (total + divisor / 2n) / divisor;
}

function dbDecimal(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return value;
  return Number(value).toFixed(2);
}

function isoFromShanghaiText(value) {
  if (!value) return null;
  return new Date(value).toISOString();
}

/* ================================================================== *
 * 4. 筛选 / cursor / requestHash
 * ================================================================== */

function buildRecordFilter(req, query, { forStatistics = false } = {}) {
  const where = [];
  const params = [];

  let state = "active";
  if (!forStatistics) {
    state = query.state === undefined || query.state === null || query.state === "" ? "active" : query.state;
    if (state !== "active" && state !== "deleted") {
      throw validationError(req, "state 无效", [fieldError("state", "INVALID_OPTION", "state 只能是 active/deleted")]);
    }
  } else if (query.state !== undefined && query.state !== null && query.state !== "") {
    throw validationError(req, "statistics 不接受 state 筛选", [fieldError("state", "INVALID_OPTION", "statistics 固定统计未删除记录")]);
  }
  where.push(state === "deleted" ? "r.deleted_at IS NOT NULL" : "r.deleted_at IS NULL");

  const { dateFrom, dateTo } = parseDateRange(req, query);
  if (dateFrom !== null) {
    where.push("r.completed_date >= ?");
    params.push(dateFrom);
  }
  if (dateTo !== null) {
    where.push("r.completed_date <= ?");
    params.push(dateTo);
  }

  const performerIds = parseIdList(req, query.performerIds, "performerIds");
  const categoryIds = parseIdList(req, query.categoryIds, "categoryIds");
  const templateIds = parseIdList(req, query.templateIds, "templateIds");
  const adHocOnly = query.adHocOnly === "true" || query.adHocOnly === "1";
  const jointOnly = query.jointOnly === "true" || query.jointOnly === "1";
  const q = parseQueryText(req, query.q, "q");

  if (adHocOnly && templateIds) {
    throw validationError(req, "筛选条件互斥", [
      fieldError("adHocOnly", "INVALID_OPTION", "adHocOnly 与 templateIds 不能同时使用")
    ]);
  }

  if (performerIds) {
    where.push(`r.record_id IN (SELECT record_id FROM housework_record_participants WHERE user_id IN (${performerIds.map(() => "?").join(",")}))`);
    params.push(...performerIds);
  }
  if (categoryIds) {
    where.push(`r.category_id_snapshot IN (${categoryIds.map(() => "?").join(",")})`);
    params.push(...categoryIds);
  }
  if (templateIds) {
    where.push(`r.template_id IN (${templateIds.map(() => "?").join(",")})`);
    params.push(...templateIds);
  }
  if (adHocOnly) where.push("r.template_id IS NULL");
  if (jointOnly) {
    where.push("(SELECT COUNT(*) FROM housework_record_participants jp WHERE jp.record_id = r.record_id) = 2");
  }
  if (q !== null) {
    const pattern = `%${likeEscape(q)}%`;
    where.push("(r.name LIKE ? ESCAPE '\\\\' OR r.note LIKE ? ESCAPE '\\\\')");
    params.push(pattern, pattern);
  }

  const normalized = {
    state: forStatistics ? null : state,
    dateFrom,
    dateTo,
    performerIds: performerIds ? [...performerIds].sort((a, b) => a - b) : null,
    categoryIds: categoryIds ? [...categoryIds].sort((a, b) => a - b) : null,
    templateIds: templateIds ? [...templateIds].sort((a, b) => a - b) : null,
    adHocOnly,
    jointOnly,
    q
  };
  return { whereClause: where.join(" AND "), params, normalized };
}

function filterHash(spaceId, normalized, limit) {
  const canonical = JSON.stringify({ spaceId, limit, ...normalized });
  return crypto.createHash("sha256").update(canonical, "utf8").digest("hex");
}

function b64url(buffer) {
  return Buffer.from(buffer).toString("base64url");
}

function signCursor(secret, payloadB64) {
  return crypto.createHmac("sha256", secret).update(payloadB64, "utf8").digest("base64url");
}

function buildCursor(secret, spaceId, revision, normalized, limit, boundary) {
  const payload = { v: 1, sp: spaceId, rev: Number(revision), fh: filterHash(spaceId, normalized, limit), b: boundary };
  const payloadB64 = b64url(JSON.stringify(payload));
  return `${payloadB64}.${signCursor(secret, payloadB64)}`;
}

function verifyCursor(req, secret, cursor, spaceId, revision, normalized, limit) {
  if (typeof cursor !== "string" || cursor.length > 4096) {
    throw reject(req, 400, "VALIDATION_ERROR", "cursor 无效");
  }
  const dot = cursor.lastIndexOf(".");
  if (dot <= 0) throw reject(req, 400, "VALIDATION_ERROR", "cursor 格式错误");
  const payloadB64 = cursor.slice(0, dot);
  const sig = cursor.slice(dot + 1);
  const expected = signCursor(secret, payloadB64);
  const sigBuf = Buffer.from(sig);
  const expBuf = Buffer.from(expected);
  if (sigBuf.length !== expBuf.length || !crypto.timingSafeEqual(sigBuf, expBuf)) {
    throw reject(req, 400, "VALIDATION_ERROR", "cursor 签名校验失败");
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(payloadB64, "base64url").toString("utf8"));
  } catch (_error) {
    throw reject(req, 400, "VALIDATION_ERROR", "cursor 内容解析失败");
  }
  if (payload.sp !== spaceId || payload.fh !== filterHash(spaceId, normalized, limit)) {
    throw reject(req, 409, "CURSOR_STALE", "筛选或空间已变化，请从第一页重新加载");
  }
  if (Number(payload.rev) !== Number(revision)) {
    throw reject(req, 409, "CURSOR_STALE", "空间数据已更新，请从第一页重新加载");
  }
  return payload.b || null;
}

/** 规范化 body（递归排序键、剔除 clientMutationId）后参与 requestHash。 */
function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (isPlainObject(value)) {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      if (key === "clientMutationId" || value[key] === undefined) continue;
      out[key] = canonicalize(value[key]);
    }
    return out;
  }
  return value;
}

function buildRequestHash({ method, resourcePath, targetId, expectedVersion, body }) {
  const canonical = JSON.stringify({
    method,
    path: resourcePath,
    targetId: targetId || null,
    expectedVersion: expectedVersion || null,
    body: canonicalize(body || {})
  });
  return crypto.createHash("sha256").update(canonical, "utf8").digest("hex");
}

/* ================================================================== *
 * 5. 授权 / 幂等 / 审计
 * ================================================================== */

function sameIdSet(a, b) {
  if (a.size !== b.size) return false;
  for (const id of a) if (!b.has(id)) return false;
  return true;
}

/**
 * 空间授权（spec 4.2 / 9）：
 *  - personal：owner == 本人；
 *  - couple：本人在 space_members + space.status/cycle/relationship 三层一致；
 *  - 非成员 403；结构不一致 409 SPACE_STATE_INVALID；closed 空间写 409 SPACE_CLOSED。
 */
async function authorizeSpace(req, executer, userId, spaceId, { forWrite = false, lock = false } = {}) {
  const lockClause = lock ? " FOR UPDATE" : "";
  const [rows] = await executer.execute(
    `SELECT s.space_id, s.scope, s.owner_user_id, s.cycle_id, s.status, s.timezone,
            s.revision, s.version, s.settings_json, s.closed_at,
            DATE_FORMAT(s.created_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS created_at,
            c.ended_at AS cycle_ended_at,
            c.member_user_id_1 AS cycle_u1, c.member_user_id_2 AS cycle_u2,
            c.relationship_id AS cycle_relationship_id,
            r.status AS relationship_status,
            r.user_id_1 AS rel_u1, r.user_id_2 AS rel_u2
     FROM housework_spaces s
     LEFT JOIN housework_relationship_cycles c ON c.cycle_id = s.cycle_id
     LEFT JOIN couple_relationships r ON r.relationship_id = c.relationship_id
     WHERE s.space_id = ?${lockClause}`,
    [spaceId]
  );
  const space = rows[0];
  if (!space) {
    throw reject(req, 404, "RESOURCE_NOT_FOUND", "空间不存在");
  }

  const [members] = await executer.execute(
    "SELECT user_id, display_name_snapshot, joined_at, ended_at FROM housework_space_members WHERE space_id = ?" + lockClause,
    [spaceId]
  );

  let canWrite = false;
  if (space.scope === "personal") {
    const isOwner = Number(space.owner_user_id) === Number(userId);
    if (!isOwner) {
      // 伴侣只读：存在有效情侣关系时，对方可读取本人个人空间（写仍仅限 owner）。
      // 关系解除后此处立即回落 403，不保留任何历史可读权限。
      const [relRows] = await executer.execute(
        `SELECT relationship_id FROM couple_relationships
         WHERE status = 'active'
           AND ((user_id_1 = ? AND user_id_2 = ?) OR (user_id_1 = ? AND user_id_2 = ?))
         LIMIT 1`,
        [space.owner_user_id, userId, userId, space.owner_user_id]
      );
      if (relRows.length === 0) {
        throw reject(req, 403, "SPACE_FORBIDDEN", "无权访问该空间");
      }
      if (forWrite) {
        throw reject(req, 403, "SPACE_FORBIDDEN", "仅本人可写入个人空间");
      }
    }
    if (space.status !== "active") {
      throw reject(req, 409, "SPACE_STATE_INVALID", "个人空间状态异常");
    }
    if (members.length !== 1 || Number(members[0].user_id) !== Number(space.owner_user_id)) {
      throw reject(req, 409, "SPACE_STATE_INVALID", "个人空间成员异常");
    }
    canWrite = isOwner;
  } else {
    const me = members.find((m) => Number(m.user_id) === Number(userId));
    if (!me) {
      throw reject(req, 403, "SPACE_FORBIDDEN", "无权访问该空间");
    }
    const cycleMembers = new Set([Number(space.cycle_u1), Number(space.cycle_u2)]);
    const relationshipMembers = new Set([Number(space.rel_u1), Number(space.rel_u2)]);
    const spaceMembers = new Set(members.map((m) => Number(m.user_id)));
    const structurallyValid =
      Boolean(space.cycle_id) &&
      cycleMembers.size === 2 &&
      (space.status !== "closed" || space.cycle_ended_at !== null) &&
      (space.status === "closed" || sameIdSet(cycleMembers, relationshipMembers)) &&
      sameIdSet(cycleMembers, spaceMembers);
    if (!structurallyValid) {
      throw reject(req, 409, "SPACE_STATE_INVALID", "空间生命周期数据不一致，禁止写入");
    }
    if (space.status === "closed") {
      if (forWrite) {
        throw reject(req, 409, "SPACE_CLOSED", "这段关系已结束，记录只读");
      }
    } else if (space.cycle_ended_at !== null || space.relationship_status !== "active") {
      if (forWrite) {
        throw reject(req, 409, "RELATIONSHIP_CHANGED", "关系已变化，请刷新空间后重试");
      }
    } else {
      canWrite = true;
    }
  }

  let settings;
  try {
    settings = typeof space.settings_json === "string" ? JSON.parse(space.settings_json) : space.settings_json;
  } catch (_error) {
    settings = { showDurationStatistics: false, showWorkloadStatistics: false };
  }
  return { space, members, canWrite, settings };
}

async function bumpSpaceRevision(executer, spaceId) {
  await executer.execute(
    "UPDATE housework_spaces SET revision = revision + 1 WHERE space_id = ?",
    [spaceId]
  );
  const [rows] = await executer.execute(
    "SELECT revision FROM housework_spaces WHERE space_id = ?",
    [spaceId]
  );
  return rows[0] ? Number(rows[0].revision) : 0;
}

/** 解析 JSON 列：mysql2 会自动把 JSON 列反序列化为对象，兼容字符串形式。 */
function parseReceiptJson(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return JSON.parse(value);
  return value;
}

/**
 * 幂等写（spec 8.5）：唯一范围 spaceId + actor + operation + mutationId。
 * 同 ID 同 hash → 回放原 result（replayed:true，不写库）；
 * 同 ID 异 hash → 409 IDEMPOTENCY_CONFLICT。并发撞唯一键时重读后按同规则处理。
 */
async function runMutation({ req, conn, userId, spaceId, operation, mutationId, requestHash, fallbackRevision, executeWrite }) {
  const selectReceipt = async () => {
    const [rows] = await conn.execute(
      `SELECT request_hash, result_id, result_json FROM housework_mutations
       WHERE space_id = ? AND actor_user_id = ? AND operation = ? AND client_mutation_id = ?`,
      [spaceId, userId, operation, mutationId]
    );
    return rows[0] || null;
  };

  const existing = await selectReceipt();
  if (existing) {
    if (existing.request_hash === requestHash) {
      return {
        replayed: true,
        result: parseReceiptJson(existing.result_json),
        resultId: existing.result_id,
        revision: fallbackRevision
      };
    }
    throw reject(req, 409, "IDEMPOTENCY_CONFLICT", "相同 clientMutationId 的请求内容不一致，请核对原提交");
  }

  const outcome = await executeWrite();
  try {
    await conn.execute(
      `INSERT INTO housework_mutations
         (space_id, actor_user_id, operation, client_mutation_id, request_hash, result_id, result_json)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [spaceId, userId, operation, mutationId, requestHash, outcome.resultId || null, JSON.stringify(outcome.result)]
    );
  } catch (error) {
    if (error && error.code === "ER_DUP_ENTRY") {
      const raced = await selectReceipt();
      if (raced && raced.request_hash === requestHash) {
        return {
          replayed: true,
          result: parseReceiptJson(raced.result_json),
          resultId: raced.result_id,
          revision: fallbackRevision
        };
      }
      throw reject(req, 409, "IDEMPOTENCY_CONFLICT", "相同 clientMutationId 的请求内容不一致，请核对原提交");
    }
    throw error;
  }
  return { replayed: false, result: outcome.result, resultId: outcome.resultId, revision: outcome.revision };
}

async function writeRecordRevision(conn, { recordId, spaceId, actorUserId, action, beforeVersion, afterVersion, changedFields, beforeJson, afterJson }) {
  await conn.execute(
    `INSERT INTO housework_record_revisions
       (record_id, space_id, actor_user_id, action, before_version, after_version, changed_fields_json, before_json, after_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      recordId,
      spaceId,
      actorUserId,
      action,
      beforeVersion,
      afterVersion,
      changedFields ? JSON.stringify(changedFields) : null,
      beforeJson ? JSON.stringify(beforeJson) : null,
      afterJson ? JSON.stringify(afterJson) : null
    ]
  );
}

async function writeConfigRevision(conn, { spaceId, entityType, entityId, actorUserId, action, beforeVersion, afterVersion, beforeJson, afterJson }) {
  await conn.execute(
    `INSERT INTO housework_config_revisions
       (space_id, entity_type, entity_id, actor_user_id, action, before_version, after_version, before_json, after_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      spaceId,
      entityType,
      entityId,
      actorUserId,
      action,
      beforeVersion,
      afterVersion,
      beforeJson ? JSON.stringify(beforeJson) : null,
      afterJson ? JSON.stringify(afterJson) : null
    ]
  );
}

/* ================================================================== *
 * 序列化
 * ================================================================== */

function parseJsonColumn(value, fallback) {
  if (value === null || value === undefined) return fallback;
  if (typeof value === "object") return value;
  try {
    return JSON.parse(value);
  } catch (_error) {
    return fallback;
  }
}

function categoryToApi(row) {
  return {
    categoryId: row.category_id,
    name: row.name,
    icon: parseJsonColumn(row.icon_json, null),
    color: row.color,
    sortOrder: Number(row.sort_order),
    isFallback: Boolean(row.is_fallback),
    status: row.status,
    presetCategoryKey: row.preset_category_key || null,
    version: Number(row.version)
  };
}

function templateToApi(row) {
  return {
    templateId: row.template_id,
    categoryId: row.category_id,
    name: row.name,
    description: row.description,
    icon: parseJsonColumn(row.icon_json, null),
    color: row.color,
    sortOrder: Number(row.sort_order),
    measureMode: row.measure_mode,
    unit: row.unit,
    defaultQuantity: dbDecimal(row.default_quantity),
    durationEnabled: Boolean(row.duration_enabled),
    defaultDurationMinutes: row.default_duration_minutes === null ? null : Number(row.default_duration_minutes),
    weight: dbDecimal(row.weight),
    fields: parseJsonColumn(row.fields_json, []),
    status: row.status,
    presetKey: row.preset_key || null,
    version: Number(row.version)
  };
}

function spaceToApi(spaceRow, { short = false } = {}) {
  const base = {
    spaceId: spaceRow.space_id,
    scope: spaceRow.scope,
    status: spaceRow.status,
    cycleId: spaceRow.cycle_id || null,
    canWrite: spaceRow.canWrite !== undefined ? Boolean(spaceRow.canWrite) : spaceRow.status === "active",
    version: Number(spaceRow.version)
  };
  if (short) return base;
  return {
    ...base,
    ownerUserId: spaceRow.owner_user_id === null || spaceRow.owner_user_id === undefined ? null : String(spaceRow.owner_user_id),
    timezone: spaceRow.timezone || TIMEZONE,
    revision: Number(spaceRow.revision),
    closedAt: isoFromShanghaiText(spaceRow.closed_at)
  };
}

// 统计聚合只需要这些列（避免为 10k 级记录集搬运 field_values/note/审计列等重物）
const STATS_RECORD_SELECT = `
  r.record_id, r.template_id, r.name, r.category_id_snapshot, r.snapshot_json,
  DATE_FORMAT(r.completed_date, '%Y-%m-%d') AS completed_date,
  r.quantity, r.duration_minutes, r.weight_snapshot
`;

const RECORD_SELECT = `
  r.record_id, r.space_id, r.template_id, r.template_version, r.name,
  r.category_id_snapshot, r.snapshot_json, r.field_values_json,
  DATE_FORMAT(r.completed_date, '%Y-%m-%d') AS completed_date,
  r.completed_time, r.quantity, r.duration_minutes, r.weight_snapshot,
  r.note, r.created_by, r.updated_by, r.client_mutation_id, r.version,
  DATE_FORMAT(r.created_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS created_at,
  DATE_FORMAT(r.updated_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS updated_at,
  DATE_FORMAT(r.deleted_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS deleted_at,
  r.deleted_by
`;

function recordToApi(row, participants) {
  const snapshot = parseJsonColumn(row.snapshot_json, {});
  const participantRows = participants
    .filter((p) => p.record_id === row.record_id)
    .map((p) => ({
      userId: String(p.user_id),
      displayName: p.display_name_snapshot,
      shareBps: Number(p.share_bps)
    }));
  return {
    recordId: row.record_id,
    spaceId: row.space_id,
    templateId: row.template_id,
    templateVersion: row.template_version === null ? null : Number(row.template_version),
    snapshot,
    name: snapshot.name || row.name,
    category: snapshot.category || { categoryId: row.category_id_snapshot, name: null, icon: null, color: null },
    measureMode: snapshot.measureMode || "event",
    unit: snapshot.unit || "次",
    weight: dbDecimal(row.weight_snapshot),
    durationEnabled: Boolean(snapshot.durationEnabled),
    completedDate: row.completed_date,
    completedTime: row.completed_time,
    quantity: dbDecimal(row.quantity),
    durationMinutes: row.duration_minutes === null ? null : Number(row.duration_minutes),
    fieldValues: parseJsonColumn(row.field_values_json, {}),
    note: row.note,
    participants: participantRows,
    createdBy: String(row.created_by),
    updatedBy: String(row.updated_by),
    clientMutationId: row.client_mutation_id,
    version: Number(row.version),
    createdAt: isoFromShanghaiText(row.created_at),
    updatedAt: isoFromShanghaiText(row.updated_at),
    deletedAt: isoFromShanghaiText(row.deleted_at),
    deletedBy: row.deleted_by === null ? null : String(row.deleted_by)
  };
}

function preferencesToApi(row) {
  return {
    spaceId: row.space_id,
    userId: String(row.user_id),
    pinnedTemplateIds: parseJsonColumn(row.pinned_template_ids_json, []),
    hiddenTemplateIds: parseJsonColumn(row.hidden_template_ids_json, []),
    orderedTemplateIds: parseJsonColumn(row.ordered_template_ids_json, []),
    layout: row.layout,
    displayAliases: parseJsonColumn(row.display_aliases_json, {}),
    defaultPerformerMode: row.default_performer_mode,
    version: Number(row.version)
  };
}

function defaultPreferences(spaceId, userId) {
  return {
    spaceId,
    userId: String(userId),
    pinnedTemplateIds: [],
    hiddenTemplateIds: [],
    orderedTemplateIds: [],
    layout: "grid",
    displayAliases: {},
    defaultPerformerMode: "self",
    version: 1
  };
}

/* ================================================================== *
 * 补充校验：模板字段定义 / 字段值 / 参与者 / 模板与分类本体
 * ================================================================== */

function parseDecimalFieldBound(raw) {
  if (raw === undefined || raw === null || raw === "") return null;
  if (typeof raw !== "string") return false;
  return raw;
}

/**
 * 校验模板字段定义数组（创建/整体替换，spec 5.3 / 8.5）。
 * existingFields：当前已存在的字段数组（含 disabled，用于禁止改类型与 optionId 校验）。
 */
function validateFieldDefinitions(req, fields, existingFields = [], historicalFields = []) {
  if (fields === undefined || fields === null) fields = [];
  if (!Array.isArray(fields)) {
    throw validationError(req, "fields 必须是数组", [fieldError("fields", "INVALID_TYPE", "fields 必须是数组")]);
  }
  const existingById = new Map(existingFields.map((f) => [f.fieldId, f]));
  const historicalById = new Map();
  for (const field of historicalFields) {
    if (!field || typeof field.fieldId !== "string") continue;
    const options = historicalById.get(field.fieldId) || new Set();
    for (const option of field.options || []) options.add(option.optionId);
    historicalById.set(field.fieldId, options);
  }
  const seenFieldIds = new Set();
  const cleaned = [];
  let enabledCount = 0;

  fields.forEach((rawField, index) => {
    const path = `fields.${index}`;
    if (!isPlainObject(rawField)) {
      throw validationError(req, "字段定义格式错误", [fieldError(path, "INVALID_TYPE", "字段必须是对象")]);
    }
    const { fieldId, label, type, required, defaultValue, sortOrder, status, unit, min, max, options } = rawField;
    if (!isUuid(fieldId)) {
      throw validationError(req, "fieldId 必须是 UUID", [fieldError(`${path}.fieldId`, "INVALID_TYPE", "fieldId 必须是 UUID")]);
    }
    if (seenFieldIds.has(fieldId)) {
      throw validationError(req, "fieldId 重复", [fieldError(`${path}.fieldId`, "DUPLICATE", "字段数组内 fieldId 必须唯一")]);
    }
    seenFieldIds.add(fieldId);
    const existing = existingById.get(fieldId);
    if (!existing && historicalById.has(fieldId)) {
      throw validationError(req, "历史 fieldId 不可重新分配", [
        fieldError(`${path}.fieldId`, "ID_REUSE_FORBIDDEN", "该 ID 已用于历史字段，请生成新的 fieldId")
      ]);
    }
    if (existing && existing.type !== type) {
      throw validationError(req, "已创建字段禁止改类型", [
        fieldError(`${path}.type`, "INVALID_OPTION", "已创建字段禁止改类型，请归档后新增 fieldId")
      ]);
    }
    if (!FIELD_TYPES.includes(type)) {
      throw validationError(req, "字段类型无效", [fieldError(`${path}.type`, "INVALID_OPTION", `type 只能是 ${FIELD_TYPES.join("/")}`)]);
    }
    if (typeof label !== "string" || codePointLength(label.trim()) < 1 || codePointLength(label.trim()) > 20) {
      throw validationError(req, "字段名称长度错误", [fieldError(`${path}.label`, "OUT_OF_RANGE", "字段名称 1–20 字符")]);
    }
    const fieldStatus = status === undefined ? "active" : status;
    if (fieldStatus !== "active" && fieldStatus !== "disabled") {
      throw validationError(req, "字段状态无效", [fieldError(`${path}.status`, "INVALID_OPTION", "status 只能是 active/disabled")]);
    }
    if (fieldStatus === "active") enabledCount += 1;

    const cleanedField = {
      fieldId,
      label: label.trim(),
      type,
      required: required === true,
      defaultValue: null,
      sortOrder: sortOrder === undefined ? index : sortOrder,
      status: fieldStatus
    };
    if (!Number.isSafeInteger(cleanedField.sortOrder) || cleanedField.sortOrder < 0) {
      throw validationError(req, "字段排序无效", [fieldError(`${path}.sortOrder`, "OUT_OF_RANGE", "sortOrder 必须是非负整数")]);
    }

    const unitBound = parseDecimalFieldBound(unit);
    const minBound = parseDecimalFieldBound(min);
    const maxBound = parseDecimalFieldBound(max);
    const hasOptions = options !== undefined && options !== null;

    if (type === "text") {
      if (unitBound !== null || minBound !== null || maxBound !== null || hasOptions) {
        throw validationError(req, "text 字段不允许 unit/min/max/options", [fieldError(path, "INVALID_OPTION", "text 字段不支持 unit/min/max/options")]);
      }
      if (defaultValue !== undefined && defaultValue !== null) {
        if (typeof defaultValue !== "string" || codePointLength(defaultValue) > 200) {
          throw validationError(req, "text 默认值无效", [fieldError(`${path}.defaultValue`, "OUT_OF_RANGE", "text 默认值最多 200 字符")]);
        }
        cleanedField.defaultValue = defaultValue;
      }
    } else if (type === "number") {
      if (hasOptions) {
        throw validationError(req, "number 字段不允许 options", [fieldError(`${path}.options`, "INVALID_OPTION", "number 字段不支持 options")]);
      }
      if (unitBound !== null) {
        if (typeof unitBound !== "string" || codePointLength(unitBound.trim()) < 1 || codePointLength(unitBound.trim()) > 8) {
          throw validationError(req, "字段单位长度错误", [fieldError(`${path}.unit`, "OUT_OF_RANGE", "单位 1–8 字符")]);
        }
        cleanedField.unit = unitBound.trim();
      }
      let minCents = null;
      let maxCents = null;
      if (minBound !== null) {
        minCents = parseDecimalCents(req, minBound, `${path}.min`, { signed: true, min: -9999999, max: 9999999 });
        cleanedField.min = formatCents(minCents);
      }
      if (maxBound !== null) {
        maxCents = parseDecimalCents(req, maxBound, `${path}.max`, { signed: true, min: -9999999, max: 9999999 });
        cleanedField.max = formatCents(maxCents);
      }
      if (minCents !== null && maxCents !== null && minCents > maxCents) {
        throw validationError(req, "字段范围无效", [fieldError(`${path}.min`, "OUT_OF_RANGE", "min 不能大于 max")]);
      }
      if (defaultValue !== undefined && defaultValue !== null) {
        const defaultCents = parseDecimalCents(req, defaultValue, `${path}.defaultValue`, { signed: true, min: -9999999, max: 9999999 });
        if (minCents !== null && defaultCents < minCents) {
          throw validationError(req, "默认值低于字段下限", [fieldError(`${path}.defaultValue`, "OUT_OF_RANGE", "默认值低于 min")]);
        }
        if (maxCents !== null && defaultCents > maxCents) {
          throw validationError(req, "默认值高于字段上限", [fieldError(`${path}.defaultValue`, "OUT_OF_RANGE", "默认值高于 max")]);
        }
        cleanedField.defaultValue = formatCents(defaultCents);
      }
    } else if (type === "single_select" || type === "multi_select") {
      if (unitBound !== null || minBound !== null || maxBound !== null) {
        throw validationError(req, "select 字段不允许 unit/min/max", [fieldError(path, "INVALID_OPTION", "select 字段不支持 unit/min/max")]);
      }
      if (!Array.isArray(options) || options.length < 1 || options.length > MAX_OPTIONS) {
        throw validationError(req, "选项数量错误", [fieldError(`${path}.options`, "OUT_OF_RANGE", `选项 1–${MAX_OPTIONS} 个`)]);
      }
      const seenOptionIds = new Set();
      const seenLabels = new Set();
      const existingOptions = existing ? new Map((existing.options || []).map((o) => [o.optionId, o])) : new Map();
      cleanedField.options = options.map((rawOption, optionIndex) => {
        const optionPath = `${path}.options.${optionIndex}`;
        if (!isPlainObject(rawOption)) {
          throw validationError(req, "选项格式错误", [fieldError(optionPath, "INVALID_TYPE", "选项必须是对象")]);
        }
        const { optionId, label: optionLabel, status: optionStatus } = rawOption;
        if (!isUuid(optionId)) {
          throw validationError(req, "optionId 必须是 UUID", [fieldError(`${optionPath}.optionId`, "INVALID_TYPE", "optionId 必须是 UUID")]);
        }
        if (seenOptionIds.has(optionId)) {
          throw validationError(req, "optionId 重复", [fieldError(`${optionPath}.optionId`, "DUPLICATE", "字段内 optionId 必须唯一")]);
        }
        seenOptionIds.add(optionId);
        const normalizedLabel = String(optionLabel || "").normalize("NFC").trim();
        if (codePointLength(normalizedLabel) < 1 || codePointLength(normalizedLabel) > 20) {
          throw validationError(req, "选项标签长度错误", [fieldError(`${optionPath}.label`, "OUT_OF_RANGE", "选项标签 1–20 字符")]);
        }
        if (seenLabels.has(normalizedLabel)) {
          throw validationError(req, "选项标签重复", [fieldError(`${optionPath}.label`, "DUPLICATE", "字段内选项标签必须唯一")]);
        }
        seenLabels.add(normalizedLabel);
        const statusValue = optionStatus === undefined ? "active" : optionStatus;
        if (statusValue !== "active" && statusValue !== "disabled") {
          throw validationError(req, "选项状态无效", [fieldError(`${optionPath}.status`, "INVALID_OPTION", "status 只能是 active/disabled")]);
        }
        if (!existingOptions.has(optionId) && historicalById.get(fieldId)?.has(optionId)) {
          throw validationError(req, "历史 optionId 不可重新分配", [
            fieldError(`${optionPath}.optionId`, "ID_REUSE_FORBIDDEN", "该 ID 已用于历史选项，请生成新的 optionId")
          ]);
        }
        return { optionId, label: normalizedLabel, status: statusValue };
      });
      if ([...existingOptions.keys()].some((optionId) => !seenOptionIds.has(optionId))) {
        throw validationError(req, "已创建选项必须保留 ID", [
          fieldError(`${path}.options`, "ID_REMOVAL_FORBIDDEN", "移除选项请设置 disabled，并保留 optionId")
        ]);
      }
      if (defaultValue !== undefined && defaultValue !== null) {
        if (type === "single_select") {
          if (typeof defaultValue !== "string" || !cleanedField.options.some((o) => o.optionId === defaultValue && o.status === "active")) {
            throw validationError(req, "select 默认值必须是启用选项", [fieldError(`${path}.defaultValue`, "INVALID_OPTION", "single_select 默认值必须引用启用选项的 optionId")]);
          }
          cleanedField.defaultValue = defaultValue;
        } else {
          if (!Array.isArray(defaultValue) || defaultValue.some((v) => typeof v !== "string") ||
              !defaultValue.every((v) => cleanedField.options.some((o) => o.optionId === v && o.status === "active"))) {
            throw validationError(req, "multi_select 默认值必须是启用选项数组", [fieldError(`${path}.defaultValue`, "INVALID_OPTION", "multi_select 默认值必须是启用 optionId 数组")]);
          }
          cleanedField.defaultValue = [...new Set(defaultValue)];
        }
      }
    } else if (type === "boolean") {
      if (unitBound !== null || minBound !== null || maxBound !== null || hasOptions) {
        throw validationError(req, "boolean 字段不允许 unit/min/max/options", [fieldError(path, "INVALID_OPTION", "boolean 字段不支持 unit/min/max/options")]);
      }
      if (defaultValue !== undefined && defaultValue !== null && typeof defaultValue !== "boolean") {
        throw validationError(req, "boolean 默认值无效", [fieldError(`${path}.defaultValue`, "INVALID_TYPE", "boolean 默认值只能是 true/false/null")]);
      }
      cleanedField.defaultValue = defaultValue === undefined ? null : defaultValue;
    }
    cleaned.push(cleanedField);
  });

  if (existingFields.some((field) => !seenFieldIds.has(field.fieldId))) {
    throw validationError(req, "已创建字段必须保留 ID", [
      fieldError("fields", "ID_REMOVAL_FORBIDDEN", "移除字段请设置 disabled，并保留 fieldId")
    ]);
  }

  if (enabledCount > MAX_ENABLED_FIELDS) {
    throw validationError(req, "启用字段超出上限", [
      fieldError("fields", "LIMIT_EXCEEDED", `最多 ${MAX_ENABLED_FIELDS} 个启用字段`)
    ]);
  }
  return cleaned;
}

/** 兼容旧客户端曾物理移除的定义：配置审计和记录快照保留的 ID 仍不得重新分配。 */
async function loadHistoricalFieldDefinitions(conn, spaceId, templateId) {
  const [revisions] = await conn.execute(
    `SELECT before_json, after_json FROM housework_config_revisions
     WHERE space_id = ? AND entity_type = 'template' AND entity_id = ?`,
    [spaceId, templateId]
  );
  const [records] = await conn.execute(
    "SELECT snapshot_json FROM housework_records WHERE space_id = ? AND template_id = ?",
    [spaceId, templateId]
  );
  const fields = [];
  for (const revision of revisions) {
    for (const json of [revision.before_json, revision.after_json]) {
      const value = parseJsonColumn(json, null);
      const definitions = value?.template?.fields;
      if (Array.isArray(definitions)) fields.push(...definitions);
    }
  }
  for (const record of records) {
    const snapshot = parseJsonColumn(record.snapshot_json, null);
    if (Array.isArray(snapshot?.fields)) fields.push(...snapshot.fields);
  }
  return fields;
}

/** 校验记录字段值。requireActiveOptions=true 用于按当前模板新建；编辑旧记录用快照定义（允许当时已归档选项）。 */
function validateFieldValues(req, rawValues, fields, { requireActiveOptions = false } = {}) {
  if (rawValues === undefined || rawValues === null) {
    return {};
  }
  if (!isPlainObject(rawValues)) {
    throw validationError(req, "fieldValues 必须是对象", [fieldError("fieldValues", "INVALID_TYPE", "fieldValues 必须是 {fieldId: value} 对象")]);
  }
  const fieldsById = new Map(fields.map((f) => [f.fieldId, f]));
  const cleaned = {};
  for (const [fieldId, value] of Object.entries(rawValues)) {
    const path = `fieldValues.${fieldId}`;
    const field = fieldsById.get(fieldId);
    if (!field || field.status !== "active") {
      throw validationError(req, "未知或已归档的 fieldId", [fieldError(path, "INVALID_OPTION", "fieldId 不存在或已归档")]);
    }
    if (value === undefined) continue;
    if (value === null) {
      if (field.required) {
        throw validationError(req, "必填字段不接受 null", [fieldError(path, "REQUIRED", "必填字段必须填写")]);
      }
      continue;
    }
    if (field.type === "text") {
      if (typeof value !== "string") {
        throw validationError(req, "字段值类型错误", [fieldError(path, "INVALID_TYPE", "text 字段值必须是字符串")]);
      }
      const trimmed = value.trim();
      if (trimmed === "" && field.required) {
        throw validationError(req, "必填文本不能为空", [fieldError(path, "REQUIRED", "必填文本 trim 后不能为空")]);
      }
      if (codePointLength(trimmed) > 200) {
        throw validationError(req, "文本过长", [fieldError(path, "OUT_OF_RANGE", "文本最多 200 字符")]);
      }
      if (trimmed !== "") cleaned[fieldId] = trimmed;
    } else if (field.type === "number") {
      const cents = parseDecimalCents(req, value, path, { signed: true, min: -9999999, max: 9999999 });
      if (field.min !== undefined && field.min !== null && cents < parseDecimalCents(req, field.min, path, { signed: true })) {
        throw validationError(req, "数值低于字段下限", [fieldError(path, "OUT_OF_RANGE", `不能小于 ${field.min}`)]);
      }
      if (field.max !== undefined && field.max !== null && cents > parseDecimalCents(req, field.max, path, { signed: true })) {
        throw validationError(req, "数值高于字段上限", [fieldError(path, "OUT_OF_RANGE", `不能大于 ${field.max}`)]);
      }
      cleaned[fieldId] = formatCents(cents);
    } else if (field.type === "single_select") {
      if (typeof value !== "string") {
        throw validationError(req, "选项值类型错误", [fieldError(path, "INVALID_TYPE", "single_select 值必须是 optionId 字符串")]);
      }
      const option = (field.options || []).find((o) => o.optionId === value);
      if (!option || (requireActiveOptions && option.status !== "active")) {
        throw validationError(req, "optionId 无效", [fieldError(path, "INVALID_OPTION", "optionId 不存在或已归档")]);
      }
      cleaned[fieldId] = value;
    } else if (field.type === "multi_select") {
      if (!Array.isArray(value) || value.some((v) => typeof v !== "string")) {
        throw validationError(req, "多选值类型错误", [fieldError(path, "INVALID_TYPE", "multi_select 值必须是 optionId 数组")]);
      }
      const unique = [...new Set(value)];
      for (const optionId of unique) {
        const option = (field.options || []).find((o) => o.optionId === optionId);
        if (!option || (requireActiveOptions && option.status !== "active")) {
          throw validationError(req, "optionId 无效", [fieldError(path, "INVALID_OPTION", `optionId ${optionId} 不存在或已归档`)]);
        }
      }
      if (field.required && unique.length === 0) {
        throw validationError(req, "必填多选至少一项", [fieldError(path, "REQUIRED", "必填 multi_select 至少选择一个有效选项")]);
      }
      if (unique.length > 0) cleaned[fieldId] = unique;
    } else if (field.type === "boolean") {
      if (typeof value !== "boolean") {
        throw validationError(req, "布尔值类型错误", [fieldError(path, "INVALID_TYPE", "boolean 值必须是 true/false")]);
      }
      cleaned[fieldId] = value;
    }
  }
  // 必填字段必须已填（false/0 是有效填写）
  for (const field of fields) {
    if (field.status === "active" && field.required && !(field.fieldId in cleaned)) {
      throw validationError(req, "必填字段未填写", [fieldError(`fieldValues.${field.fieldId}`, "REQUIRED", `必填字段「${field.label}」未填写`)]);
    }
  }
  return cleaned;
}

function validateParticipants(req, rawParticipants, members) {
  if (!Array.isArray(rawParticipants) || rawParticipants.length < 1 || rawParticipants.length > 2) {
    throw validationError(req, "参与者必须是 1–2 名空间成员", [
      fieldError("participants", "OUT_OF_RANGE", "参与者必须是 1 或 2 名空间成员")
    ]);
  }
  const memberIds = new Set(members.map((m) => Number(m.user_id)));
  const displayById = new Map(members.map((m) => [Number(m.user_id), m.display_name_snapshot]));
  const seen = new Set();
  const parsed = [];
  rawParticipants.forEach((raw, index) => {
    const path = `participants.${index}`;
    if (!isPlainObject(raw)) {
      throw validationError(req, "参与者格式错误", [fieldError(path, "INVALID_TYPE", "参与者必须是 {userId, shareBps} 对象")]);
    }
    const userIdNum = Number(raw.userId);
    if (!Number.isSafeInteger(userIdNum) || !memberIds.has(userIdNum)) {
      throw validationError(req, "参与者必须是空间成员", [fieldError(`${path}.userId`, "INVALID_OPTION", "只能指定本空间成员，不能指定第三人")]);
    }
    if (seen.has(userIdNum)) {
      throw validationError(req, "参与者重复", [fieldError(`${path}.userId`, "DUPLICATE", "参与者不能重复")]);
    }
    seen.add(userIdNum);
    let shareBps = raw.shareBps;
    if (rawParticipants.length === 1 && (shareBps === undefined || shareBps === null)) {
      shareBps = 10000;
    }
    const bps = typeof shareBps === "number" ? shareBps : Number(String(shareBps));
    if (!Number.isSafeInteger(bps)) {
      throw validationError(req, "份额必须是整数", [fieldError(`${path}.shareBps`, "INVALID_TYPE", "shareBps 必须是整数基点")]);
    }
    parsed.push({ userId: userIdNum, shareBps: bps, displayName: displayById.get(userIdNum) });
  });
  if (parsed.length === 1) {
    if (parsed[0].shareBps !== 10000) {
      throw validationError(req, "单人记录份额必须为 100%", [fieldError("participants.0.shareBps", "OUT_OF_RANGE", "单人记录固定 10000")]);
    }
  } else {
    const [a, b] = parsed;
    for (const p of parsed) {
      if (p.shareBps < 100 || p.shareBps > 9900) {
        throw validationError(req, "共同记录份额必须是 1%–99%", [
          fieldError("participants", "OUT_OF_RANGE", "两人共同完成时各自份额为 100–9900")
        ]);
      }
    }
    if (a.shareBps + b.shareBps !== 10000) {
      throw validationError(req, "份额之和必须为 100%", [
        fieldError("participants.1.shareBps", "OUT_OF_RANGE", "两人份额之和必须等于 10000")
      ]);
    }
  }
  return parsed;
}

function validateTemplateName(req, name, path = "name") {
  if (typeof name !== "string" || codePointLength(name.trim()) < 1 || codePointLength(name.trim()) > 40) {
    throw validationError(req, "模板名称长度错误", [fieldError(path, "OUT_OF_RANGE", "名称 1–40 字符")]);
  }
  return name.trim();
}

async function loadCategoryRow(conn, spaceId, categoryId, { lock = false } = {}) {
  const [rows] = await conn.execute(
    `SELECT * FROM housework_categories WHERE category_id = ? AND space_id = ? LIMIT 1${lock ? " FOR UPDATE" : ""}`,
    [categoryId, spaceId]
  );
  return rows[0] || null;
}

async function loadTemplateRow(conn, spaceId, templateId, { lock = false } = {}) {
  const [rows] = await conn.execute(
    `SELECT * FROM housework_templates WHERE template_id = ? AND space_id = ? LIMIT 1${lock ? " FOR UPDATE" : ""}`,
    [templateId, spaceId]
  );
  return rows[0] || null;
}

/** 同空间同分类未归档名称大小写不敏感去重。 */
async function ensureTemplateNameAvailable(req, conn, spaceId, categoryId, normalizedName, excludeId = null) {
  const params = [spaceId, categoryId, normalizedName];
  let sql = `SELECT template_id FROM housework_templates
             WHERE space_id = ? AND category_id = ? AND normalized_name = ? AND status = 'active'`;
  if (excludeId) {
    sql += " AND template_id != ?";
    params.push(excludeId);
  }
  sql += " LIMIT 1";
  const [rows] = await conn.execute(sql, params);
  if (rows.length > 0) {
    throw validationError(req, "同分类下已存在同名模板", [fieldError("name", "DUPLICATE_NAME", "同空间同分类内名称不能重复")]);
  }
}

/** 通用写响应：{operation, entity, revision, replayed}。 */
function respondWrite(res, revision, replayed, result) {
  res.json({ ok: true, data: { ...result, revision, replayed } });
}

function createHouseworkRouter({ pool, config }) {
  const router = express.Router();
  const cursorSecret = (config && config.jwtSecret) || process.env.JWT_SECRET || "housework-cursor-dev-secret";

  router.use((req, _res, next) => {
    req.requestId = uuid();
    next();
  });

  /** 写端点公共骨架：事务 + 授权（先于幂等回放）+ 幂等执行。executeWrite 需返回 {resultId, result, revision}。 */
  async function performWrite(req, spaceId, operation, mutationId, requestHash, executeWrite) {
    return withTransaction(pool, async (conn) => {
      const auth = await authorizeSpace(req, conn, req.userId, spaceId, { forWrite: true, lock: true });
      return runMutation({
        req,
        conn,
        userId: req.userId,
        spaceId,
        operation,
        mutationId,
        requestHash,
        fallbackRevision: Number(auth.space.revision),
        executeWrite: () => executeWrite(conn, auth)
      });
    });
  }

  /* ================================================================ *
   * 9. 空间与 bootstrap
   * ================================================================ */

  router.post("/bootstrap", async (req, res, next) => {
    try {
      const userId = req.userId;
      const outcome = await withTransaction(pool, async (conn) => {
        const [meRows] = await conn.execute(
          "SELECT id, username, nickname FROM users WHERE id = ? LIMIT 1 FOR UPDATE",
          [userId]
        );
        if (meRows.length === 0) {
          throw reject(req, 401, "UNAUTHORIZED", "用户不存在");
        }
        const displayName = pickDisplayName(meRows[0]);
        const personalSpaceId = await ensurePersonalSpace(conn, userId, displayName);

        let sharedInfo = null;
        const [relRows] = await conn.execute(
          `SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships
           WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?)
           ORDER BY relationship_id DESC LIMIT 1 FOR UPDATE`,
          [userId, userId]
        );
        if (relRows.length > 0) {
          const rel = relRows[0];
          const names = await loadDisplayNames(conn, [rel.user_id_1, rel.user_id_2]);
          sharedInfo = await ensureSharedSpaceForBootstrap(conn, rel, names);
        }
        return { personalSpaceId, sharedInfo };
      });

      // 空间列表与 GET /spaces 同口径：本人个人 + 伴侣个人（只读）+ 全部成员空间（含历史归档），
      // 保证重绑后旧归档空间仍出现在列表里（spec 4.1.4 / 8.3）。
      // 伴侣个人空间随当前有效关系存在而出现，解绑后自动消失。
      const [bootRelRows] = await pool.execute(
        `SELECT user_id_1, user_id_2 FROM couple_relationships
         WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?)
         ORDER BY relationship_id DESC LIMIT 1`,
        [userId, userId]
      );
      const bootPartnerId = bootRelRows.length
        ? (Number(bootRelRows[0].user_id_1) === Number(userId) ? bootRelRows[0].user_id_2 : bootRelRows[0].user_id_1)
        : 0;
      const [spaceRows] = await pool.execute(
        `SELECT s.space_id, s.scope, s.owner_user_id, s.cycle_id, s.status, s.timezone, s.revision, s.version, s.closed_at
         FROM housework_spaces s
         LEFT JOIN housework_space_members m ON m.space_id = s.space_id AND m.user_id = ?
         WHERE (s.scope = 'personal' AND (s.owner_user_id = ? OR s.owner_user_id = ?))
            OR (s.scope = 'couple' AND m.user_id IS NOT NULL)`,
        [userId, userId, bootPartnerId]
      );
      const spaces = spaceRows.map((row) => {
        const api = spaceToApi(row);
        if (api.scope === 'personal' && String(api.ownerUserId) !== String(userId)) api.canWrite = false;
        return api;
      });
      const sharedActive = spaces.find((s) => s.scope === "couple" && s.status === "active");
      const time = nowContext();
      res.json({
        ok: true,
        data: {
          spaces,
          defaultSpaceId: sharedActive ? sharedActive.spaceId : outcome.personalSpaceId,
          currentCycleId: sharedActive ? sharedActive.cycleId : null,
          serverNow: time.serverNow,
          today: time.today,
          timezone: time.timezone
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces", async (req, res, next) => {
    try {
      const userId = req.userId;
      const [relRows] = await pool.execute(
        `SELECT user_id_1, user_id_2 FROM couple_relationships
         WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?)
         ORDER BY relationship_id DESC LIMIT 1`,
        [userId, userId]
      );
      const partnerId = relRows.length
        ? (Number(relRows[0].user_id_1) === Number(userId) ? relRows[0].user_id_2 : relRows[0].user_id_1)
        : 0;
      const [rows] = await pool.execute(
        `SELECT s.space_id, s.scope, s.owner_user_id, s.cycle_id, s.status, s.timezone, s.revision, s.version, s.closed_at
         FROM housework_spaces s
         LEFT JOIN housework_space_members m ON m.space_id = s.space_id AND m.user_id = ?
         WHERE s.scope = 'personal' AND (s.owner_user_id = ? OR s.owner_user_id = ?)
            OR (s.scope = 'couple' AND m.user_id IS NOT NULL)`,
        [userId, userId, partnerId]
      );
      const spaces = rows.map((row) => {
        const api = spaceToApi(row);
        if (api.scope === 'personal' && String(api.ownerUserId) !== String(userId)) api.canWrite = false;
        return api;
      });
      const sharedActive = spaces.find((s) => s.scope === "couple" && s.status === "active");
      const time = nowContext();
      res.json({
        ok: true,
        data: {
          spaces,
          defaultSpaceId: sharedActive ? sharedActive.spaceId : (spaces.find((s) => s.scope === "personal") || {}).spaceId || null,
          currentCycleId: sharedActive ? sharedActive.cycleId : null,
          serverNow: time.serverNow,
          today: time.today,
          timezone: time.timezone
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/config", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const time = nowContext();
        const spaceShort = spaceToApi({ ...auth.space, canWrite: auth.canWrite }, { short: true });
        const knownRevision = req.query.knownRevision;
        if (knownRevision !== undefined && knownRevision !== null && knownRevision !== "" &&
            Number(knownRevision) === Number(auth.space.revision)) {
          return {
            space: spaceShort,
            revision: Number(auth.space.revision),
            serverNow: time.serverNow,
            today: time.today,
            timezone: time.timezone,
            unchanged: true
          };
        }
        const [categoryRows] = await conn.execute(
          `SELECT * FROM housework_categories
           WHERE space_id = ? AND status = 'active'
           ORDER BY sort_order ASC, category_id ASC LIMIT ${MAX_ACTIVE_CATEGORIES}`,
          [spaceId]
        );
        const [templateRows] = await conn.execute(
          `SELECT * FROM housework_templates
           WHERE space_id = ? AND status = 'active'
           ORDER BY sort_order ASC, template_id ASC LIMIT ${MAX_ACTIVE_TEMPLATES}`,
          [spaceId]
        );
        const [prefRows] = await conn.execute(
          "SELECT * FROM housework_preferences WHERE space_id = ? AND user_id = ? LIMIT 1",
          [spaceId, userId]
        );
        return {
          space: spaceShort,
          settings: {
            showDurationStatistics: Boolean(auth.settings.showDurationStatistics),
            showWorkloadStatistics: Boolean(auth.settings.showWorkloadStatistics)
          },
          settingsVersion: Number(auth.space.version),
          members: auth.members.map((m) => ({ userId: String(m.user_id), displayName: m.display_name_snapshot })),
          categories: categoryRows.map(categoryToApi),
          templates: templateRows.map(templateToApi),
          preferences: prefRows.length > 0 ? preferencesToApi(prefRows[0]) : defaultPreferences(spaceId, userId),
          revision: Number(auth.space.revision),
          serverNow: time.serverNow,
          today: time.today,
          timezone: time.timezone,
          unchanged: false
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 6a. 空间设置
   * ================================================================ */

  router.patch("/spaces/:spaceId/settings", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const hasDuration = body.showDurationStatistics !== undefined;
      const hasWorkload = body.showWorkloadStatistics !== undefined;
      if (!hasDuration && !hasWorkload) {
        throw validationError(req, "至少提供一项设置", [
          fieldError("showDurationStatistics", "REQUIRED", "showDurationStatistics / showWorkloadStatistics 至少提供一项")
        ]);
      }
      if (hasDuration && typeof body.showDurationStatistics !== "boolean") {
        throw validationError(req, "设置值必须是布尔", [fieldError("showDurationStatistics", "INVALID_TYPE", "必须是 boolean")]);
      }
      if (hasWorkload && typeof body.showWorkloadStatistics !== "boolean") {
        throw validationError(req, "设置值必须是布尔", [fieldError("showWorkloadStatistics", "INVALID_TYPE", "必须是 boolean")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/spaces/:spaceId/settings",
        targetId: "settings",
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.SETTINGS_UPDATE, mutationId, requestHash, async (conn, auth) => {
            const settings = {
              showDurationStatistics: hasDuration ? body.showDurationStatistics : Boolean(auth.settings.showDurationStatistics),
              showWorkloadStatistics: hasWorkload ? body.showWorkloadStatistics : Boolean(auth.settings.showWorkloadStatistics)
            };
            const [result] = await conn.execute(
              `UPDATE housework_spaces SET settings_json = ?, version = version + 1
               WHERE space_id = ? AND version = ?`,
              [JSON.stringify(settings), spaceId, expectedVersion]
            );
            if (result.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "设置已被他人修改，请刷新后重试", {
                currentVersion: Number(auth.space.version),
                entityType: "settings",
                entityId: "settings"
              });
            }
            const newVersion = expectedVersion + 1;
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "settings",
              entityId: "settings",
              actorUserId: userId,
              action: "update",
              beforeVersion: expectedVersion,
              afterVersion: newVersion,
              beforeJson: { settings: auth.settings },
              afterJson: { settings }
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: "settings",
              result: {
                operation: OPERATIONS.SETTINGS_UPDATE,
                entity: { settings, settingsVersion: newVersion }
              },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 6b. 分类
   * ================================================================ */

  router.post("/spaces/:spaceId/categories", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const name = typeof body.name === "string" ? body.name.trim() : "";
      if (codePointLength(name) < 1 || codePointLength(name) > 20) {
        throw validationError(req, "分类名称长度错误", [fieldError("name", "OUT_OF_RANGE", "名称 1–20 字符")]);
      }
      const icon = validateIcon(req, body.icon, "icon");
      const color = validateColor(req, body.color, "color");
      const sortOrder = parseSortOrder(req, body.sortOrder, "sortOrder");
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/spaces/:spaceId/categories",
        targetId: null,
        expectedVersion: null,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.CATEGORIES_CREATE, mutationId, requestHash, async (conn, auth) => {
            const [countRows] = await conn.execute(
              "SELECT COUNT(*) AS total FROM housework_categories WHERE space_id = ? AND status = 'active'",
              [spaceId]
            );
            if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
              throw validationError(req, "分类数量已达上限", [
                fieldError("name", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个未归档分类`)
              ]);
            }
            const normalized = normalizeName(name);
            const [dupes] = await conn.execute(
              `SELECT category_id FROM housework_categories
               WHERE space_id = ? AND normalized_name = ? AND status = 'active' LIMIT 1`,
              [spaceId, normalized]
            );
            if (dupes.length > 0) {
              throw validationError(req, "分类名称重复", [fieldError("name", "DUPLICATE_NAME", "同空间未归档分类名称不能重复")]);
            }
            let order = sortOrder;
            if (order === null) {
              const [maxRows] = await conn.execute(
                "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM housework_categories WHERE space_id = ?",
                [spaceId]
              );
              order = Number(maxRows[0].max_order) + 1;
            }
            const categoryId = uuid();
            await conn.execute(
              `INSERT INTO housework_categories
                 (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'active')`,
              [categoryId, spaceId, name, normalized, icon ? JSON.stringify(icon) : null, color, order]
            );
            const categoryRow = await loadCategoryRow(conn, spaceId, categoryId);
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "category",
              entityId: categoryId,
              actorUserId: userId,
              action: "create",
              beforeVersion: null,
              afterVersion: 1,
              afterJson: { category: categoryToApi(categoryRow) }
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: categoryId,
              result: { operation: OPERATIONS.CATEGORIES_CREATE, entity: categoryToApi(categoryRow) },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/categories", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const status = parseStatusFilter(req, req.query.status);
      const q = parseQueryText(req, req.query.q, "q");
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const normalized = { status, q, limit };
        const where = ["space_id = ?"];
        const params = [spaceId];
        if (status !== "all") {
          where.push("status = ?");
          params.push(status);
        }
        if (q !== null) {
          where.push("name LIKE ? ESCAPE '\\\\'");
          params.push(`%${likeEscape(q)}%`);
        }
        const whereClause = where.join(" AND ");
        let boundary = null;
        if (req.query.cursor) {
          boundary = verifyCursor(req, cursorSecret, req.query.cursor, spaceId, auth.space.revision, normalized, limit);
        }
        let continuation = "";
        const continuationParams = [];
        if (boundary) {
          continuation = " AND (sort_order > ? OR (sort_order = ? AND category_id > ?))";
          continuationParams.push(boundary.o, boundary.o, boundary.id);
        }
        const [countRows] = await conn.execute(
          `SELECT COUNT(*) AS total FROM housework_categories WHERE ${whereClause}`,
          params
        );
        const [rows] = await conn.execute(
          `SELECT * FROM housework_categories WHERE ${whereClause}${continuation}
           ORDER BY sort_order ASC, category_id ASC LIMIT ${limit + 1}`,
          [...params, ...continuationParams]
        );
        const hasMore = rows.length > limit;
        const items = rows.slice(0, limit).map(categoryToApi);
        const nextCursor = hasMore
          ? buildCursor(cursorSecret, spaceId, auth.space.revision, normalized, limit, {
              o: rows[limit - 1].sort_order,
              id: rows[limit - 1].category_id
            })
          : null;
        return { items, total: Number(countRows[0].total), nextCursor, revision: Number(auth.space.revision) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.put("/spaces/:spaceId/categories/order", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedRevision = parseExpectedRevision(req, body.expectedRevision);
      const activeCategoryIds = body.activeCategoryIds;
      if (!Array.isArray(activeCategoryIds) || activeCategoryIds.length > MAX_ACTIVE_CATEGORIES) {
        throw validationError(req, "分类顺序无效", [fieldError("activeCategoryIds", "INVALID_TYPE", `activeCategoryIds 必须是最多 ${MAX_ACTIVE_CATEGORIES} 项的完整 ID 数组`)]);
      }
      if (activeCategoryIds.some((id) => !isUuid(id))) {
        throw validationError(req, "分类顺序包含无效 ID", [fieldError("activeCategoryIds", "INVALID_TYPE", "每个分类 ID 必须是 UUID")]);
      }
      const requestedSet = new Set(activeCategoryIds);
      if (requestedSet.size !== activeCategoryIds.length) {
        throw validationError(req, "分类顺序包含重复 ID", [fieldError("activeCategoryIds", "DUPLICATE", "分类 ID 不能重复")]);
      }
      const requestHash = buildRequestHash({
        method: "PUT",
        resourcePath: "/spaces/:spaceId/categories/order",
        targetId: null,
        expectedVersion: expectedRevision,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.CATEGORIES_ORDER, mutationId, requestHash, async (conn, auth) => {
        if (expectedRevision !== Number(auth.space.revision)) {
          throw reject(req, 409, "VERSION_CONFLICT", "分类或空间配置已更新，请刷新后重新排序", {
            currentVersion: Number(auth.space.revision),
            entityType: "categoryCollection",
            entityId: spaceId
          });
        }

        const [categoryRows] = await conn.execute(
          `SELECT * FROM housework_categories
           WHERE space_id = ? AND status = 'active'
           ORDER BY sort_order ASC, category_id ASC FOR UPDATE`,
          [spaceId]
        );
        const currentIds = categoryRows.map((row) => row.category_id);
        if (!sameIdSet(requestedSet, new Set(currentIds))) {
          throw validationError(req, "分类集合已变化", [fieldError("activeCategoryIds", "INVALID_OPTION", "必须提交该空间所有启用分类的完整顺序")]);
        }

        const rowsById = new Map(categoryRows.map((row) => [row.category_id, row]));
        const orderedCategories = [];
        let changed = false;
        for (let index = 0; index < activeCategoryIds.length; index += 1) {
          const row = rowsById.get(activeCategoryIds[index]);
          const beforeSortOrder = Number(row.sort_order);
          const beforeVersion = Number(row.version);
          if (beforeSortOrder !== index) {
            const beforeApi = categoryToApi(row);
            const [updateResult] = await conn.execute(
              `UPDATE housework_categories
               SET sort_order = ?, version = version + 1
               WHERE category_id = ? AND space_id = ? AND status = 'active' AND version = ?`,
              [index, row.category_id, spaceId, beforeVersion]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "分类已被他人修改，请刷新后重新排序", {
                currentVersion: Number(auth.space.revision),
                entityType: "categoryCollection",
                entityId: spaceId
              });
            }
            const afterRow = { ...row, sort_order: index, version: beforeVersion + 1 };
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "category",
              entityId: row.category_id,
              actorUserId: userId,
              action: "order",
              beforeVersion,
              afterVersion: beforeVersion + 1,
              beforeJson: { category: beforeApi },
              afterJson: { category: categoryToApi(afterRow) }
            });
            orderedCategories.push(categoryToApi(afterRow));
            changed = true;
          } else {
            orderedCategories.push(categoryToApi(row));
          }
        }
        const revision = changed ? await bumpSpaceRevision(conn, spaceId) : Number(auth.space.revision);
        return {
          resultId: spaceId,
          result: {
            operation: OPERATIONS.CATEGORIES_ORDER,
            entity: { activeCategoryIds: activeCategoryIds.slice(), categories: orderedCategories }
          },
          revision
        };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.patch("/spaces/:spaceId/categories/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      if (body.status !== undefined && body.status !== null && body.status !== "active" && body.status !== "archived") {
        throw validationError(req, "状态无效", [fieldError("status", "INVALID_OPTION", "status 只能是 active/archived")]);
      }
      const hasName = body.name !== undefined && body.name !== null;
      const name = hasName ? (typeof body.name === "string" ? body.name.trim() : "") : null;
      if (hasName && (codePointLength(name) < 1 || codePointLength(name) > 20)) {
        throw validationError(req, "分类名称长度错误", [fieldError("name", "OUT_OF_RANGE", "名称 1–20 字符")]);
      }
      const icon = body.icon !== undefined ? validateIcon(req, body.icon, "icon") : undefined;
      const color = body.color !== undefined ? validateColor(req, body.color, "color") : undefined;
      const sortOrder = body.sortOrder !== undefined ? parseSortOrder(req, body.sortOrder, "sortOrder") : undefined;
      const targetStatus = body.status;
      if (targetStatus === undefined && !hasName && icon === undefined && color === undefined && sortOrder === undefined) {
        throw validationError(req, "至少提供一项修改", [fieldError("name", "REQUIRED", "没有可更新的字段")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/spaces/:spaceId/categories/:id",
        targetId: id,
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.CATEGORIES_UPDATE, mutationId, requestHash, async (conn, auth) => {
            const category = await loadCategoryRow(conn, spaceId, id, { lock: true });
            if (!category) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
            }
            const currentVersion = Number(category.version);
            const beforeApi = categoryToApi(category);
            const isArchived = category.status === "archived";

            if (isArchived && targetStatus !== "active") {
              throw reject(req, 409, "STATE_CONFLICT", "已归档分类只能恢复，不能修改其他配置");
            }
            if (targetStatus === "archived" && category.is_fallback) {
              throw validationError(req, "兜底分类不可归档", [fieldError("status", "INVALID_OPTION", "固定兜底分类不可归档")]);
            }

            let moveTarget = null;
            if (targetStatus === "archived") {
              const [activeTemplates] = await conn.execute(
                "SELECT COUNT(*) AS total FROM housework_templates WHERE space_id = ? AND category_id = ? AND status = 'active'",
                [spaceId, id]
              );
              if (Number(activeTemplates[0].total) > 0) {
                const moveId = body.moveTemplatesToCategoryId;
                if (!isUuid(moveId)) {
                  throw validationError(req, "归档前必须指定模板迁移目标分类", [
                    fieldError("moveTemplatesToCategoryId", "REQUIRED", "该分类下还有启用模板，必须选择未归档目标分类")
                  ]);
                }
                moveTarget = await loadCategoryRow(conn, spaceId, moveId, { lock: true });
                if (!moveTarget || moveTarget.status !== "active" || moveTarget.category_id === id) {
                  throw validationError(req, "模板迁移目标分类无效", [
                    fieldError("moveTemplatesToCategoryId", "INVALID_OPTION", "目标必须是同空间其他未归档分类")
                  ]);
                }
              }
            }

            const finalStatus = targetStatus || category.status;
            if (hasName || finalStatus === "active") {
              const finalName = hasName ? name : category.name;
              const normalized = normalizeName(finalName);
              const [dupes] = await conn.execute(
                `SELECT category_id FROM housework_categories
                 WHERE space_id = ? AND normalized_name = ? AND status = 'active' AND category_id != ? LIMIT 1`,
                [spaceId, normalized, id]
              );
              if (dupes.length > 0) {
                throw validationError(req, "分类名称重复", [fieldError("name", "DUPLICATE_NAME", "同空间未归档分类名称不能重复")]);
              }
            }
            if (finalStatus === "active" && category.status === "archived") {
              const [countRows] = await conn.execute(
                "SELECT COUNT(*) AS total FROM housework_categories WHERE space_id = ? AND status = 'active'",
                [spaceId]
              );
              if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
                throw validationError(req, "分类数量已达上限", [
                  fieldError("status", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个未归档分类`)
                ]);
              }
            }

            const newName = hasName ? name : category.name;
            const newNormalized = normalizeName(newName);
            const newIcon = icon === undefined ? parseJsonColumn(category.icon_json, null) : icon;
            const newColor = color === undefined ? category.color : color;
            const newSort = sortOrder === undefined ? Number(category.sort_order) : sortOrder;
            const [updateResult] = await conn.execute(
              `UPDATE housework_categories
               SET name = ?, normalized_name = ?, icon_json = ?, color = ?, sort_order = ?, status = ?, version = version + 1
               WHERE category_id = ? AND space_id = ? AND version = ?`,
              [newName, newNormalized, newIcon ? JSON.stringify(newIcon) : null, newColor, newSort, finalStatus, id, spaceId, expectedVersion]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "分类已被他人修改，请刷新后重试", {
                currentVersion: currentVersion,
                entityType: "category",
                entityId: id
              });
            }

            if (moveTarget) {
              const [movedTemplates] = await conn.execute(
                "SELECT * FROM housework_templates WHERE space_id = ? AND category_id = ? AND status = 'active'",
                [spaceId, id]
              );
              for (const template of movedTemplates) {
                await conn.execute(
                  "UPDATE housework_templates SET category_id = ?, version = version + 1 WHERE template_id = ?",
                  [moveTarget.category_id, template.template_id]
                );
                await writeConfigRevision(conn, {
                  spaceId,
                  entityType: "template",
                  entityId: template.template_id,
                  actorUserId: userId,
                  action: "move_templates",
                  beforeVersion: Number(template.version),
                  afterVersion: Number(template.version) + 1,
                  beforeJson: { categoryId: id },
                  afterJson: { categoryId: moveTarget.category_id }
                });
              }
            }

            const categoryRow = await loadCategoryRow(conn, spaceId, id);
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "category",
              entityId: id,
              actorUserId: userId,
              action: targetStatus === "archived" ? "archive" : targetStatus === "active" && category.status === "archived" ? "restore" : "update",
              beforeVersion: expectedVersion,
              afterVersion: expectedVersion + 1,
              beforeJson: { category: beforeApi },
              afterJson: { category: categoryToApi(categoryRow) }
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: id,
              result: { operation: OPERATIONS.CATEGORIES_UPDATE, entity: categoryToApi(categoryRow) },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 6c. 模板
   * ================================================================ */

  function parseTemplatePayload(req, body, { partial = false } = {}) {
    const errors = [];
    const out = {};
    const has = (key) => body[key] !== undefined && body[key] !== null;

    if (!partial || has("name")) {
      if (typeof body.name !== "string" || codePointLength(body.name.trim()) < 1 || codePointLength(body.name.trim()) > 40) {
        errors.push(fieldError("name", "OUT_OF_RANGE", "名称 1–40 字符"));
      } else {
        out.name = body.name.trim();
      }
    }
    if (!partial || body.categoryId !== undefined) {
      if (!isUuid(body.categoryId)) {
        errors.push(fieldError("categoryId", has("categoryId") ? "INVALID_TYPE" : "REQUIRED", "categoryId 必须是 UUID"));
      } else {
        out.categoryId = body.categoryId;
      }
    }
    if (!partial || has("measureMode")) {
      if (body.measureMode !== "event" && body.measureMode !== "quantity") {
        errors.push(fieldError("measureMode", "INVALID_OPTION", "measureMode 只能是 event/quantity"));
      } else {
        out.measureMode = body.measureMode;
      }
    }
    if (errors.length > 0) throw validationError(req, "模板参数无效", errors);

    if (body.presetKey !== undefined && body.presetKey !== null) {
      throw validationError(req, "不允许写 presetKey", [fieldError("presetKey", "INVALID_OPTION", "presetKey 由服务端管理")]);
    }
    if (has("description")) {
      if (typeof body.description !== "string" || codePointLength(body.description) > 300) {
        throw validationError(req, "说明过长", [fieldError("description", "OUT_OF_RANGE", "说明最多 300 字符")]);
      }
      out.description = body.description.trim() === "" ? null : body.description.trim();
    }
    if (body.icon !== undefined) out.icon = validateIcon(req, body.icon, "icon");
    if (body.color !== undefined) out.color = validateColor(req, body.color, "color");
    if (body.sortOrder !== undefined) out.sortOrder = parseSortOrder(req, body.sortOrder, "sortOrder");

    const mode = out.measureMode;
    if (!partial || body.unit !== undefined) {
      if (mode === "event") {
        out.unit = "次";
      } else if (typeof body.unit !== "string" || codePointLength(body.unit.trim()) < 1 || codePointLength(body.unit.trim()) > 8) {
        throw validationError(req, "单位长度错误", [fieldError("unit", "OUT_OF_RANGE", "数量模式单位必填，1–8 字符")]);
      } else {
        out.unit = body.unit.trim();
      }
    }
    if (!partial || body.defaultQuantity !== undefined) {
      if (mode === "event") {
        // event 模式强制 quantity=1/unit=次（spec 5.2/8.5），即使客户端未传
        out.defaultQuantity = "1.00";
      } else if (body.defaultQuantity === undefined || body.defaultQuantity === null) {
        out.defaultQuantity = null;
      } else {
        out.defaultQuantity = formatCents(parseDecimalCents(req, body.defaultQuantity, "defaultQuantity", { min: 1, max: 9999999 }));
      }
    }
    if (!partial || body.durationEnabled !== undefined) {
      if (body.durationEnabled !== undefined && body.durationEnabled !== null && typeof body.durationEnabled !== "boolean") {
        throw validationError(req, "durationEnabled 必须是布尔", [fieldError("durationEnabled", "INVALID_TYPE", "必须是 boolean")]);
      }
      out.durationEnabled = body.durationEnabled === true;
    }
    if (!partial || body.defaultDurationMinutes !== undefined) {
      if (body.defaultDurationMinutes === undefined || body.defaultDurationMinutes === null || out.durationEnabled === false) {
        out.defaultDurationMinutes = null;
      } else {
        const minutes = Number(body.defaultDurationMinutes);
        if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 1440) {
          throw validationError(req, "默认耗时超出范围", [fieldError("defaultDurationMinutes", "OUT_OF_RANGE", "耗时必须是 1–1440 的整数分钟")]);
        }
        out.defaultDurationMinutes = minutes;
      }
    }
    if (!partial || body.weight !== undefined) {
      if (body.weight === undefined || body.weight === null) {
        out.weight = "1.00";
      } else {
        out.weight = formatCents(parseDecimalCents(req, body.weight, "weight", { min: 1, max: 10000 }));
      }
    }
    if (!partial || body.fields !== undefined) {
      out.fields = body.fields; // 字段数组整体替换，复用同一校验器
    }
    return out;
  }

  router.post("/spaces/:spaceId/templates", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const sourceTemplateId = body.sourceTemplateId;
      let name;
      if (typeof body.name === "string" && codePointLength(body.name.trim()) >= 1) {
        name = validateTemplateName(req, body.name);
      }
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/spaces/:spaceId/templates",
        targetId: null,
        expectedVersion: null,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.TEMPLATES_CREATE, mutationId, requestHash, async (conn, auth) => {
            let payload;
            if (sourceTemplateId !== undefined && sourceTemplateId !== null) {
              if (!isUuid(sourceTemplateId)) {
                throw validationError(req, "sourceTemplateId 无效", [fieldError("sourceTemplateId", "INVALID_TYPE", "必须是 UUID")]);
              }
              const source = await loadTemplateRow(conn, spaceId, sourceTemplateId);
              if (!source) {
                throw reject(req, 404, "RESOURCE_NOT_FOUND", "源模板不存在");
              }
              if (!name) {
                throw validationError(req, "复制模板必须提供新名称", [fieldError("name", "REQUIRED", "复制模板必须显式指定新名称")]);
              }
              payload = {
                name,
                categoryId: source.category_id,
                description: source.description,
                icon: parseJsonColumn(source.icon_json, null),
                color: source.color,
                measureMode: source.measure_mode,
                unit: source.unit,
                defaultQuantity: dbDecimal(source.default_quantity),
                durationEnabled: Boolean(source.duration_enabled),
                defaultDurationMinutes: source.default_duration_minutes,
                weight: dbDecimal(source.weight),
                fields: parseJsonColumn(source.fields_json, [])
              };
            } else {
              payload = parseTemplatePayload(req, body);
            }

            const category = await loadCategoryRow(conn, spaceId, payload.categoryId);
            if (!category) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
            }
            if (category.status !== "active") {
              throw validationError(req, "不能归入归档分类", [fieldError("categoryId", "INVALID_OPTION", "归档分类不能新增模板，请先恢复分类")]);
            }
            const [countRows] = await conn.execute(
              "SELECT COUNT(*) AS total FROM housework_templates WHERE space_id = ? AND status = 'active'",
              [spaceId]
            );
            if (Number(countRows[0].total) >= MAX_ACTIVE_TEMPLATES) {
              throw validationError(req, "模板数量已达上限", [
                fieldError("name", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_TEMPLATES} 个启用模板`)
              ]);
            }
            await ensureTemplateNameAvailable(req, conn, spaceId, payload.categoryId, normalizeName(payload.name));

            let fieldsJson = "[]";
            if (payload.fields !== undefined && payload.fields !== null) {
              const cleanedFields = validateFieldDefinitions(req, payload.fields, []);
              fieldsJson = JSON.stringify(cleanedFields);
            }
            let order = payload.sortOrder;
            if (order === undefined || order === null) {
              const [maxRows] = await conn.execute(
                "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM housework_templates WHERE space_id = ?",
                [spaceId]
              );
              order = Number(maxRows[0].max_order) + 1;
            }
            const templateId = uuid();
            await conn.execute(
              `INSERT INTO housework_templates
                 (template_id, space_id, category_id, name, normalized_name, description, icon_json, color,
                  sort_order, measure_mode, unit, default_quantity, duration_enabled, default_duration_minutes,
                  weight, fields_json, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')`,
              [
                templateId,
                spaceId,
                payload.categoryId,
                payload.name,
                normalizeName(payload.name),
                payload.description || null,
                payload.icon ? JSON.stringify(payload.icon) : null,
                payload.color || null,
                order,
                payload.measureMode,
                payload.unit,
                payload.defaultQuantity,
                payload.durationEnabled ? 1 : 0,
                payload.defaultDurationMinutes,
                payload.weight,
                fieldsJson
              ]
            );
            const templateRow = await loadTemplateRow(conn, spaceId, templateId);
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "template",
              entityId: templateId,
              actorUserId: userId,
              action: "create",
              beforeVersion: null,
              afterVersion: 1,
              afterJson: { template: templateToApi(templateRow) }
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: templateId,
              result: { operation: OPERATIONS.TEMPLATES_CREATE, entity: templateToApi(templateRow) },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/templates", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const status = parseStatusFilter(req, req.query.status);
      const q = parseQueryText(req, req.query.q, "q");
      let categoryId = req.query.categoryId;
      if (categoryId !== undefined && categoryId !== null && categoryId !== "" && !isUuid(categoryId)) {
        throw validationError(req, "categoryId 无效", [fieldError("categoryId", "INVALID_TYPE", "必须是 UUID")]);
      }
      if (categoryId === "") categoryId = null;
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const normalized = { status, categoryId: categoryId || null, q, limit };
        const where = ["space_id = ?"];
        const params = [spaceId];
        if (status !== "all") {
          where.push("status = ?");
          params.push(status);
        }
        if (categoryId) {
          where.push("category_id = ?");
          params.push(categoryId);
        }
        if (q !== null) {
          where.push("name LIKE ? ESCAPE '\\\\'");
          params.push(`%${likeEscape(q)}%`);
        }
        const whereClause = where.join(" AND ");
        let boundary = null;
        if (req.query.cursor) {
          boundary = verifyCursor(req, cursorSecret, req.query.cursor, spaceId, auth.space.revision, normalized, limit);
        }
        let continuation = "";
        const continuationParams = [];
        if (boundary) {
          continuation = " AND (sort_order > ? OR (sort_order = ? AND template_id > ?))";
          continuationParams.push(boundary.o, boundary.o, boundary.id);
        }
        const [countRows] = await conn.execute(
          `SELECT COUNT(*) AS total FROM housework_templates WHERE ${whereClause}`,
          params
        );
        const [rows] = await conn.execute(
          `SELECT * FROM housework_templates WHERE ${whereClause}${continuation}
           ORDER BY sort_order ASC, template_id ASC LIMIT ${limit + 1}`,
          [...params, ...continuationParams]
        );
        const hasMore = rows.length > limit;
        const items = rows.slice(0, limit).map(templateToApi);
        const nextCursor = hasMore
          ? buildCursor(cursorSecret, spaceId, auth.space.revision, normalized, limit, {
              o: rows[limit - 1].sort_order,
              id: rows[limit - 1].template_id
            })
          : null;
        return { items, total: Number(countRows[0].total), nextCursor, revision: Number(auth.space.revision) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/templates/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      await withTransaction(pool, async (conn) => {
        await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const template = await loadTemplateRow(conn, spaceId, id);
        if (!template) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "模板不存在");
        }
        res.json({ ok: true, data: templateToApi(template) });
      });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/spaces/:spaceId/templates/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      if (body.presetKey !== undefined && body.presetKey !== null) {
        throw validationError(req, "不允许写 presetKey", [fieldError("presetKey", "INVALID_OPTION", "presetKey 由服务端管理")]);
      }
      if (body.status !== undefined && body.status !== null && body.status !== "active" && body.status !== "archived") {
        throw validationError(req, "状态无效", [fieldError("status", "INVALID_OPTION", "status 只能是 active/archived")]);
      }
      const patchKeys = ["name", "categoryId", "description", "icon", "color", "sortOrder", "measureMode", "unit",
        "defaultQuantity", "durationEnabled", "defaultDurationMinutes", "weight", "fields", "status"];
      if (!patchKeys.some((key) => body[key] !== undefined)) {
        throw validationError(req, "至少提供一项修改", [fieldError("name", "REQUIRED", "没有可更新的字段")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/spaces/:spaceId/templates/:id",
        targetId: id,
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.TEMPLATES_UPDATE, mutationId, requestHash, async (conn, auth) => {
            const template = await loadTemplateRow(conn, spaceId, id, { lock: true });
            if (!template) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "模板不存在");
            }
            const currentVersion = Number(template.version);
            const beforeApi = templateToApi(template);
            const isArchived = template.status === "archived";
            const targetStatus = body.status;
            if (isArchived && targetStatus !== "active") {
              throw reject(req, 409, "STATE_CONFLICT", "已归档模板只能恢复，不能修改其他配置");
            }

            const merged = {
              name: body.name !== undefined ? validateTemplateName(req, body.name) : template.name,
              categoryId: body.categoryId !== undefined ? body.categoryId : template.category_id,
              description: body.description !== undefined
                ? (typeof body.description === "string" && codePointLength(body.description) <= 300 ? (body.description.trim() === "" ? null : body.description.trim()) : null)
                : template.description,
              icon: body.icon !== undefined ? validateIcon(req, body.icon, "icon") : parseJsonColumn(template.icon_json, null),
              color: body.color !== undefined ? validateColor(req, body.color, "color") : template.color,
              sortOrder: body.sortOrder !== undefined ? parseSortOrder(req, body.sortOrder, "sortOrder") : Number(template.sort_order),
              measureMode: body.measureMode !== undefined ? body.measureMode : template.measure_mode,
              unit: template.unit,
              defaultQuantity: dbDecimal(template.default_quantity),
              durationEnabled: body.durationEnabled !== undefined ? body.durationEnabled === true : Boolean(template.duration_enabled),
              defaultDurationMinutes: template.default_duration_minutes === null ? null : Number(template.default_duration_minutes),
              weight: dbDecimal(template.weight),
              fields: parseJsonColumn(template.fields_json, [])
            };
            if (body.measureMode !== undefined && body.measureMode !== "event" && body.measureMode !== "quantity") {
              throw validationError(req, "计量模式无效", [fieldError("measureMode", "INVALID_OPTION", "measureMode 只能是 event/quantity")]);
            }
            if (merged.measureMode === "event") {
              merged.unit = "次";
              merged.defaultQuantity = "1.00";
            } else {
              if (body.unit !== undefined) {
                if (typeof body.unit !== "string" || codePointLength(body.unit.trim()) < 1 || codePointLength(body.unit.trim()) > 8) {
                  throw validationError(req, "单位长度错误", [fieldError("unit", "OUT_OF_RANGE", "数量模式单位必填，1–8 字符")]);
                }
                merged.unit = body.unit.trim();
              } else {
                merged.unit = template.unit;
              }
              if (body.defaultQuantity !== undefined) {
                merged.defaultQuantity = body.defaultQuantity === null
                  ? null
                  : formatCents(parseDecimalCents(req, body.defaultQuantity, "defaultQuantity", { min: 1, max: 9999999 }));
              }
            }
            if (body.defaultDurationMinutes !== undefined) {
              if (body.defaultDurationMinutes === null || merged.durationEnabled === false) {
                merged.defaultDurationMinutes = null;
              } else {
                const minutes = Number(body.defaultDurationMinutes);
                if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 1440) {
                  throw validationError(req, "默认耗时超出范围", [fieldError("defaultDurationMinutes", "OUT_OF_RANGE", "耗时必须是 1–1440 的整数分钟")]);
                }
                merged.defaultDurationMinutes = minutes;
              }
            }
            if (merged.durationEnabled === false) merged.defaultDurationMinutes = null;
            if (body.weight !== undefined) {
              merged.weight = body.weight === null
                ? "1.00"
                : formatCents(parseDecimalCents(req, body.weight, "weight", { min: 1, max: 10000 }));
            }
            if (body.description !== undefined && merged.description === null && typeof body.description === "string" && codePointLength(body.description) > 300) {
              throw validationError(req, "说明过长", [fieldError("description", "OUT_OF_RANGE", "说明最多 300 字符")]);
            }

            const category = await loadCategoryRow(conn, spaceId, merged.categoryId);
            if (!category) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
            }
            const restoring = targetStatus === "active" && isArchived;
            if (category.status !== "active") {
              throw validationError(req, "不能归入归档分类", [fieldError("categoryId", "INVALID_OPTION", "归档分类不能启用模板，请先恢复分类")]);
            }
            if (restoring) {
              const [countRows] = await conn.execute(
                "SELECT COUNT(*) AS total FROM housework_templates WHERE space_id = ? AND status = 'active'",
                [spaceId]
              );
              if (Number(countRows[0].total) >= MAX_ACTIVE_TEMPLATES) {
                throw validationError(req, "模板数量已达上限", [
                  fieldError("status", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_TEMPLATES} 个启用模板`)
                ]);
              }
            }
            await ensureTemplateNameAvailable(req, conn, spaceId, merged.categoryId, normalizeName(merged.name), id);

            if (body.fields !== undefined) {
              const historicalFields = await loadHistoricalFieldDefinitions(conn, spaceId, id);
              merged.fields = validateFieldDefinitions(req, body.fields, parseJsonColumn(template.fields_json, []), historicalFields);
            }

            const finalStatus = targetStatus || template.status;
            const [updateResult] = await conn.execute(
              `UPDATE housework_templates
               SET name = ?, normalized_name = ?, description = ?, icon_json = ?, color = ?, sort_order = ?,
                   measure_mode = ?, unit = ?, default_quantity = ?, duration_enabled = ?, default_duration_minutes = ?,
                   weight = ?, fields_json = ?, status = ?, version = version + 1
               WHERE template_id = ? AND space_id = ? AND version = ?`,
              [
                merged.name,
                normalizeName(merged.name),
                merged.description,
                merged.icon ? JSON.stringify(merged.icon) : null,
                merged.color,
                merged.sortOrder,
                merged.measureMode,
                merged.unit,
                merged.defaultQuantity,
                merged.durationEnabled ? 1 : 0,
                merged.defaultDurationMinutes,
                merged.weight,
                JSON.stringify(merged.fields),
                finalStatus,
                id,
                spaceId,
                expectedVersion
              ]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "模板已被他人修改，请刷新后重试", {
                currentVersion: currentVersion,
                entityType: "template",
                entityId: id
              });
            }
            const templateRow = await loadTemplateRow(conn, spaceId, id);
            await writeConfigRevision(conn, {
              spaceId,
              entityType: "template",
              entityId: id,
              actorUserId: userId,
              action: targetStatus === "archived" ? "archive" : restoring ? "restore" : "update",
              beforeVersion: expectedVersion,
              afterVersion: expectedVersion + 1,
              beforeJson: { template: beforeApi },
              afterJson: { template: templateToApi(templateRow) }
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: id,
              result: { operation: OPERATIONS.TEMPLATES_UPDATE, entity: templateToApi(templateRow) },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 6d. 推荐包
   * ================================================================ */

  router.post("/spaces/:spaceId/presets/apply", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      if (!Array.isArray(body.selectedPresetKeys) || body.selectedPresetKeys.length < 1) {
        throw validationError(req, "selectedPresetKeys 必填", [
          fieldError("selectedPresetKeys", "REQUIRED", "selectedPresetKeys 必须是非空数组")
        ]);
      }
      if (body.selectedPresetKeys.length > MAX_PRESET_KEYS) {
        throw validationError(req, "推荐项数量超限", [
          fieldError("selectedPresetKeys", "OUT_OF_RANGE", `一次最多导入 ${MAX_PRESET_KEYS} 个推荐项`)
        ]);
      }
      const keys = [...new Set(body.selectedPresetKeys)];
      for (const key of keys) {
        if (typeof key !== "string" || !PRESET_KEY_SET.has(key)) {
          throw validationError(req, "存在未知推荐项", [fieldError("selectedPresetKeys", "INVALID_OPTION", `未知 presetKey: ${key}`)]);
        }
      }
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/spaces/:spaceId/presets/apply",
        targetId: null,
        expectedVersion: null,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.PRESETS_APPLY, mutationId, requestHash, async (conn, auth) => {
            const imported = [];
            const skipped = [];
            const archivedConflict = [];

            for (const key of keys) {
              const [existingTemplates] = await conn.execute(
                "SELECT template_id, status FROM housework_templates WHERE space_id = ? AND preset_key = ? LIMIT 1",
                [spaceId, key]
              );
              if (existingTemplates.length > 0) {
                if (existingTemplates[0].status === "active") skipped.push(key);
                else archivedConflict.push(key);
                continue;
              }
              const pack = PRESET_PACKS.find((p) => p.templates.some((t) => t.presetKey === key));
              const preset = pack.templates.find((t) => t.presetKey === key);

              // 推荐分类按稳定 key 幂等建立：改名后不重复建分类
              let categoryId;
              const [existingCategories] = await conn.execute(
                "SELECT category_id, status FROM housework_categories WHERE space_id = ? AND preset_category_key = ? LIMIT 1",
                [spaceId, pack.presetCategoryKey]
              );
              if (existingCategories.length > 0) {
                categoryId = existingCategories[0].category_id;
              } else {
                const [countRows] = await conn.execute(
                  "SELECT COUNT(*) AS total FROM housework_categories WHERE space_id = ? AND status = 'active'",
                  [spaceId]
                );
                if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
                  throw validationError(req, "分类数量已达上限", [
                    fieldError("selectedPresetKeys", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个未归档分类`)
                  ]);
                }
                categoryId = uuid();
                await conn.execute(
                  `INSERT INTO housework_categories
                     (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback, status, preset_category_key)
                   VALUES (?, ?, ?, ?, ?, ?, 0, 0, 'active', ?)`,
                  [categoryId, spaceId, pack.name, normalizeName(pack.name), JSON.stringify(pack.icon), pack.color, pack.presetCategoryKey]
                );
                await writeConfigRevision(conn, {
                  spaceId,
                  entityType: "category",
                  entityId: categoryId,
                  actorUserId: userId,
                  action: "create",
                  beforeVersion: null,
                  afterVersion: 1,
                  afterJson: { presetCategoryKey: pack.presetCategoryKey }
                });
              }

              const fields = validateFieldDefinitions(req, preset.fields || [], []);
              const templateId = uuid();
              try {
                await conn.execute(
                  `INSERT INTO housework_templates
                     (template_id, space_id, category_id, name, normalized_name, description, icon_json, color,
                      sort_order, measure_mode, unit, default_quantity, duration_enabled, default_duration_minutes,
                      weight, fields_json, status, preset_key)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
                  [
                    templateId,
                    spaceId,
                    categoryId,
                    preset.name,
                    normalizeName(preset.name),
                    preset.description || null,
                    null,
                    pack.color,
                    preset.measureMode,
                    preset.measureMode === "event" ? "次" : preset.unit,
                    preset.measureMode === "event" ? "1.00" : (preset.defaultQuantity || null),
                    preset.durationEnabled ? 1 : 0,
                    preset.defaultDurationMinutes || null,
                    preset.weight,
                    JSON.stringify(fields),
                    key
                  ]
                );
              } catch (error) {
                if (error && error.code === "ER_DUP_ENTRY") {
                  // 并发导入撞唯一键：按已存在处理
                  const [raced] = await conn.execute(
                    "SELECT status FROM housework_templates WHERE space_id = ? AND preset_key = ? LIMIT 1",
                    [spaceId, key]
                  );
                  if (raced.length > 0 && raced[0].status === "active") skipped.push(key);
                  else archivedConflict.push(key);
                  continue;
                }
                throw error;
              }
              await writeConfigRevision(conn, {
                spaceId,
                entityType: "template",
                entityId: templateId,
                actorUserId: userId,
                action: "create",
                beforeVersion: null,
                afterVersion: 1,
                afterJson: { presetKey: key }
              });
              imported.push({ presetKey: key, templateId });
            }

            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: null,
              result: {
                operation: OPERATIONS.PRESETS_APPLY,
                entity: { imported, skipped, archivedConflict }
              },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 6e. 个人偏好
   * ================================================================ */

  router.patch("/spaces/:spaceId/preferences", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const patchKeys = ["pinnedTemplateIds", "hiddenTemplateIds", "orderedTemplateIds", "layout", "displayAliases", "defaultPerformerMode"];
      if (!patchKeys.some((key) => body[key] !== undefined)) {
        throw validationError(req, "至少提供一项偏好修改", [fieldError("pinnedTemplateIds", "REQUIRED", "没有可更新的字段")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/spaces/:spaceId/preferences",
        targetId: String(userId),
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.PREFERENCES_UPDATE, mutationId, requestHash, async (conn, auth) => {
            const [rows] = await conn.execute(
              "SELECT * FROM housework_preferences WHERE space_id = ? AND user_id = ? LIMIT 1 FOR UPDATE",
              [spaceId, userId]
            );
            const current = rows.length > 0 ? preferencesToApi(rows[0]) : defaultPreferences(spaceId, userId);
            const currentVersion = current.version;
            if (expectedVersion !== currentVersion) {
              throw reject(req, 409, "VERSION_CONFLICT", "偏好已被修改，请刷新后重试", {
                currentVersion: currentVersion,
                entityType: "preferences",
                entityId: String(userId)
              });
            }

            const validateTemplateIdArray = (value, path, { max = null } = {}) => {
              if (!Array.isArray(value)) {
                throw validationError(req, "偏好必须是数组", [fieldError(path, "INVALID_TYPE", "必须是字符串数组")]);
              }
              const ids = [...new Set(value)];
              if (max !== null && ids.length > max) {
                throw validationError(req, "偏好数量超限", [fieldError(path, "LIMIT_EXCEEDED", `最多 ${max} 项`)]);
              }
              for (const id of ids) {
                if (!isUuid(id)) {
                  throw validationError(req, "模板 ID 无效", [fieldError(path, "INVALID_TYPE", "模板 ID 必须是 UUID")]);
                }
              }
              return ids;
            };

            const next = { ...current };
            if (body.pinnedTemplateIds !== undefined) next.pinnedTemplateIds = validateTemplateIdArray(body.pinnedTemplateIds, "pinnedTemplateIds", { max: MAX_PINNED });
            if (body.hiddenTemplateIds !== undefined) next.hiddenTemplateIds = validateTemplateIdArray(body.hiddenTemplateIds, "hiddenTemplateIds");
            if (body.orderedTemplateIds !== undefined) next.orderedTemplateIds = validateTemplateIdArray(body.orderedTemplateIds, "orderedTemplateIds");
            if (body.layout !== undefined) {
              if (body.layout !== "grid" && body.layout !== "list") {
                throw validationError(req, "布局无效", [fieldError("layout", "INVALID_OPTION", "layout 只能是 grid/list")]);
              }
              next.layout = body.layout;
            }
            if (body.defaultPerformerMode !== undefined) {
              if (body.defaultPerformerMode !== "self" && body.defaultPerformerMode !== "choose") {
                throw validationError(req, "默认执行者模式无效", [fieldError("defaultPerformerMode", "INVALID_OPTION", "只能是 self/choose")]);
              }
              next.defaultPerformerMode = body.defaultPerformerMode;
            }
            if (body.displayAliases !== undefined) {
              if (!isPlainObject(body.displayAliases)) {
                throw validationError(req, "称呼必须是对象", [fieldError("displayAliases", "INVALID_TYPE", "displayAliases 必须是 {userId: 称呼} 对象")]);
              }
              const memberIds = new Set(auth.members.map((m) => String(m.user_id)));
              if (auth.space.scope === "personal") memberIds.add(String(userId));
              const aliases = {};
              for (const [memberId, alias] of Object.entries(body.displayAliases)) {
                if (!memberIds.has(String(memberId))) {
                  throw validationError(req, "称呼只能设置给本空间成员", [
                    fieldError(`displayAliases.${memberId}`, "INVALID_OPTION", "只能给本空间成员设置称呼")
                  ]);
                }
                if (alias === null || alias === undefined || alias === "") continue;
                if (typeof alias !== "string" || codePointLength(alias.trim()) < 1 || codePointLength(alias.trim()) > 12) {
                  throw validationError(req, "称呼长度错误", [
                    fieldError(`displayAliases.${memberId}`, "OUT_OF_RANGE", "称呼 1–12 字符")
                  ]);
                }
                aliases[String(memberId)] = alias.trim();
              }
              next.displayAliases = aliases;
            }

            // 拒绝非本空间模板 ID（同时校验 pinned/hidden/ordered 的交集集合）
            const referencedIds = [...new Set([
              ...next.pinnedTemplateIds,
              ...next.hiddenTemplateIds,
              ...next.orderedTemplateIds
            ])];
            if (referencedIds.length > 0) {
              const idPlaceholders = referencedIds.map(() => "?").join(", ");
              const [templateRows] = await conn.execute(
                `SELECT template_id FROM housework_templates WHERE space_id = ? AND template_id IN (${idPlaceholders})`,
                [spaceId, ...referencedIds]
              );
              const validIds = new Set(templateRows.map((row) => row.template_id));
              for (const id of referencedIds) {
                if (!validIds.has(id)) {
                  throw validationError(req, "引用了其他空间的模板", [
                    fieldError("pinnedTemplateIds", "INVALID_OPTION", `模板 ${id} 不属于该空间`)
                  ]);
                }
              }
            }

            const newVersion = currentVersion + (rows.length > 0 ? 1 : 0);
            await conn.execute(
              `INSERT INTO housework_preferences
                 (space_id, user_id, pinned_template_ids_json, hidden_template_ids_json, ordered_template_ids_json,
                  layout, display_aliases_json, default_performer_mode, version)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON DUPLICATE KEY UPDATE
                 pinned_template_ids_json = VALUES(pinned_template_ids_json),
                 hidden_template_ids_json = VALUES(hidden_template_ids_json),
                 ordered_template_ids_json = VALUES(ordered_template_ids_json),
                 layout = VALUES(layout),
                 display_aliases_json = VALUES(display_aliases_json),
                 default_performer_mode = VALUES(default_performer_mode),
                 version = VALUES(version)`,
              [
                spaceId,
                userId,
                JSON.stringify(next.pinnedTemplateIds),
                JSON.stringify(next.hiddenTemplateIds),
                JSON.stringify(next.orderedTemplateIds),
                next.layout,
                JSON.stringify(next.displayAliases),
                next.defaultPerformerMode,
                newVersion
              ]
            );
            next.version = newVersion;
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: String(userId),
              result: { operation: OPERATIONS.PREFERENCES_UPDATE, entity: next },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 7. 记录
   * ================================================================ */

  async function loadRecordRow(conn, spaceId, recordId, { lock = false } = {}) {
    const [rows] = await conn.execute(
      `SELECT ${RECORD_SELECT} FROM housework_records r
       WHERE r.record_id = ? AND r.space_id = ? LIMIT 1${lock ? " FOR UPDATE" : ""}`,
      [recordId, spaceId]
    );
    return rows[0] || null;
  }

  async function loadParticipantsByRecordIds(conn, recordIds) {
    if (recordIds.length === 0) return [];
    const recordPlaceholders = recordIds.map(() => "?").join(", ");
    const [rows] = await conn.execute(
      `SELECT p.record_id, p.user_id, p.share_bps, p.display_name_snapshot
       FROM housework_record_participants p
       WHERE p.record_id IN (${recordPlaceholders}) ORDER BY p.user_id ASC`,
      recordIds
    );
    return rows;
  }

  function recordContinuation(boundary) {
    if (!boundary) return { clause: "", params: [] };
    const { d, t, c, r } = boundary;
    if (t === null || t === undefined) {
      return {
        clause: ` AND (r.completed_date < ? OR (r.completed_date = ? AND r.completed_time IS NULL
          AND (r.created_at < ? OR (r.created_at = ? AND r.record_id < ?))))`,
        params: [d, d, c, c, r]
      };
    }
    return {
      clause: ` AND (r.completed_date < ?
        OR (r.completed_date = ? AND (r.completed_time < ?
          OR (r.completed_time = ? AND r.created_at < ?)
          OR (r.completed_time = ? AND r.created_at = ? AND r.record_id < ?)
          OR r.completed_time IS NULL)))`,
      params: [d, d, t, t, c, t, c, r]
    };
  }

  router.get("/spaces/:spaceId/records", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const filter = buildRecordFilter(req, req.query);
        const normalized = { ...filter.normalized, limit };
        const baseWhere = `r.space_id = ? AND ${filter.whereClause}`;
        const baseParams = [spaceId, ...filter.params];

        let boundary = null;
        if (req.query.cursor) {
          boundary = verifyCursor(req, cursorSecret, req.query.cursor, spaceId, auth.space.revision, normalized, limit);
        }
        const isDeletedMode = filter.normalized.state === "deleted";
        const continuation = isDeletedMode
          ? (boundary
              ? {
                  clause: " AND (r.deleted_at < ? OR (r.deleted_at = ? AND r.record_id < ?))",
                  params: [boundary.c, boundary.c, boundary.r]
                }
              : { clause: "", params: [] })
          : recordContinuation(boundary);

        const orderClause = isDeletedMode
          ? "r.deleted_at DESC, r.record_id DESC"
          : "r.completed_date DESC, r.completed_time DESC, r.created_at DESC, r.record_id DESC";
        const cursorTimeExpr = isDeletedMode
          ? "DATE_FORMAT(r.deleted_at, '%Y-%m-%d %H:%i:%s.%f')"
          : "DATE_FORMAT(r.created_at, '%Y-%m-%d %H:%i:%s.%f')";

        const [countRows] = await conn.execute(
          `SELECT COUNT(*) AS total FROM housework_records r WHERE ${baseWhere}`,
          baseParams
        );
        const [rows] = await conn.execute(
          `SELECT ${RECORD_SELECT}, ${cursorTimeExpr} AS cursor_time
           FROM housework_records r WHERE ${baseWhere}${continuation.clause}
           ORDER BY ${orderClause} LIMIT ${limit + 1}`,
          [...baseParams, ...continuation.params]
        );
        const hasMore = rows.length > limit;
        const pageRows = rows.slice(0, limit);
        const participants = await loadParticipantsByRecordIds(conn, pageRows.map((row) => row.record_id));
        const items = pageRows.map((row) => recordToApi(row, participants));
        let nextCursor = null;
        if (hasMore) {
          const last = pageRows[pageRows.length - 1];
          nextCursor = buildCursor(cursorSecret, spaceId, auth.space.revision, normalized, limit, {
            d: last.completed_date,
            t: last.completed_time,
            c: last.cursor_time,
            r: last.record_id
          });
        }
        return { items, total: Number(countRows[0].total), nextCursor, revision: Number(auth.space.revision) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  /** 组装记录快照（创建时由可信模板/临时结构生成，spec 8.2）。 */
  async function buildRecordSnapshot(req, conn, spaceId, body) {
    const hasTemplate = body.templateId !== undefined && body.templateId !== null;
    const hasAdHoc = body.adHoc !== undefined && body.adHoc !== null;
    if (hasTemplate === hasAdHoc) {
      throw validationError(req, "templateId 与 adHoc 必须二选一", [
        fieldError("templateId", "INVALID_OPTION", "模板记录与临时记录互斥")
      ]);
    }
    if (hasTemplate) {
      if (!isUuid(body.templateId)) {
        throw validationError(req, "templateId 无效", [fieldError("templateId", "INVALID_TYPE", "templateId 必须是 UUID")]);
      }
      if (body.templateVersion === undefined || body.templateVersion === null) {
        throw validationError(req, "缺少 templateVersion", [fieldError("templateVersion", "REQUIRED", "templateVersion 必填")]);
      }
      const templateVersion = Number(body.templateVersion);
      if (!Number.isSafeInteger(templateVersion) || templateVersion < 1) {
        throw validationError(req, "templateVersion 无效", [fieldError("templateVersion", "INVALID_TYPE", "templateVersion 必须是正整数")]);
      }
      const template = await loadTemplateRow(conn, spaceId, body.templateId, { lock: true });
      if (!template) {
        throw reject(req, 404, "RESOURCE_NOT_FOUND", "模板不存在");
      }
      if (template.status !== "active") {
        throw reject(req, 409, "TEMPLATE_UNAVAILABLE", "模板已归档，不能用于新增记录", {
          entityType: "template",
          entityId: template.template_id
        });
      }
      const currentVersion = Number(template.version);
      if (templateVersion !== currentVersion) {
        throw reject(req, 409, "TEMPLATE_VERSION_CONFLICT", "模板定义已更新，请刷新后重填", {
          currentVersion,
          entityType: "template",
          entityId: template.template_id
        });
      }
      const category = await loadCategoryRow(conn, spaceId, template.category_id);
      const snapshot = {
        name: template.name,
        category: category
          ? {
              categoryId: category.category_id,
              name: category.name,
              icon: parseJsonColumn(category.icon_json, null),
              color: category.color
            }
          : { categoryId: template.category_id, name: null, icon: null, color: null },
        measureMode: template.measure_mode,
        unit: template.unit,
        durationEnabled: Boolean(template.duration_enabled),
        weight: dbDecimal(template.weight),
        fields: parseJsonColumn(template.fields_json, [])
      };
      return {
        kind: "template",
        templateId: template.template_id,
        templateVersion: currentVersion,
        snapshot,
        fields: snapshot.fields,
        requireActiveOptions: true,
        measureMode: template.measure_mode,
        unit: template.unit,
        durationEnabled: Boolean(template.duration_enabled)
      };
    }

    const adHoc = body.adHoc;
    if (!isPlainObject(adHoc)) {
      throw validationError(req, "adHoc 必须是对象", [fieldError("adHoc", "INVALID_TYPE", "adHoc 必须是 {name, categoryId, measureMode, ...} 对象")]);
    }
    const name = validateTemplateName(req, adHoc.name, "adHoc.name");
    if (!isUuid(adHoc.categoryId)) {
      throw validationError(req, "adHoc.categoryId 无效", [fieldError("adHoc.categoryId", "REQUIRED", "adHoc.categoryId 必须是 UUID")]);
    }
    if (adHoc.measureMode !== "event" && adHoc.measureMode !== "quantity") {
      throw validationError(req, "adHoc.measureMode 无效", [fieldError("adHoc.measureMode", "INVALID_OPTION", "只能是 event/quantity")]);
    }
    const category = await loadCategoryRow(conn, spaceId, adHoc.categoryId);
    if (!category) {
      throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
    }
    if (category.status !== "active") {
      throw validationError(req, "不能归入归档分类", [fieldError("adHoc.categoryId", "INVALID_OPTION", "临时记录必须选择启用分类")]);
    }
    const measureMode = adHoc.measureMode;
    let unit = "次";
    if (measureMode === "quantity") {
      if (typeof adHoc.unit !== "string" || codePointLength(adHoc.unit.trim()) < 1 || codePointLength(adHoc.unit.trim()) > 8) {
        throw validationError(req, "adHoc.unit 长度错误", [fieldError("adHoc.unit", "OUT_OF_RANGE", "数量模式单位必填，1–8 字符")]);
      }
      unit = adHoc.unit.trim();
    }
    const weight = adHoc.weight === undefined || adHoc.weight === null
      ? "1.00"
      : formatCents(parseDecimalCents(req, adHoc.weight, "adHoc.weight", { min: 1, max: 10000 }));
    const snapshot = {
      name,
      category: {
        categoryId: category.category_id,
        name: category.name,
        icon: parseJsonColumn(category.icon_json, null),
        color: category.color
      },
      measureMode,
      unit,
      // Temporary records always expose an optional duration input. A null
      // durationMinutes still represents an unfilled/unknown duration.
      durationEnabled: true,
      weight,
      fields: []
    };
    return {
      kind: "adHoc",
      templateId: null,
      templateVersion: null,
      snapshot,
      fields: [],
      requireActiveOptions: false,
      measureMode,
      unit,
      durationEnabled: true
    };
  }

  function validateRecordMeasures(req, sourceInfo, body) {
    let quantity;
    if (sourceInfo.measureMode === "event") {
      quantity = "1.00";
    } else {
      if (body.quantity === undefined || body.quantity === null) {
        throw validationError(req, "数量必填", [fieldError("quantity", "REQUIRED", "数量模式必须填写数量")]);
      }
      quantity = formatCents(parseDecimalCents(req, body.quantity, "quantity", { min: 1, max: 9999999 }));
    }
    let durationMinutes = null;
    if (body.durationMinutes !== undefined && body.durationMinutes !== null) {
      if (!sourceInfo.durationEnabled) {
        throw validationError(req, "该记录未开启耗时", [
          fieldError("durationMinutes", "INVALID_OPTION", "模板/临时记录未开启耗时统计，不能填写时长")
        ]);
      }
      const minutes = typeof body.durationMinutes === "number" ? body.durationMinutes : Number(String(body.durationMinutes));
      if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 1440) {
        throw validationError(req, "耗时超出范围", [fieldError("durationMinutes", "OUT_OF_RANGE", "耗时必须是 1–1440 的整数分钟")]);
      }
      durationMinutes = minutes;
    }
    return { quantity, durationMinutes };
  }

  function validateNote(req, raw) {
    if (raw === undefined || raw === null || raw === "") return null;
    if (typeof raw !== "string" || codePointLength(raw) > 500) {
      throw validationError(req, "备注过长", [fieldError("note", "OUT_OF_RANGE", "备注最多 500 字符")]);
    }
    const trimmed = raw.trim();
    return trimmed === "" ? null : trimmed;
  }

  router.post("/spaces/:spaceId/records", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/spaces/:spaceId/records",
        targetId: null,
        expectedVersion: null,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.RECORDS_CREATE, mutationId, requestHash, async (conn, auth) => {
            const sourceInfo = await buildRecordSnapshot(req, conn, spaceId, body);
            const completedDate = parseCompletedDate(req, body.completedDate);
            const completedTime = parseCompletedTime(req, body.completedTime, completedDate);
            const participants = validateParticipants(req, body.participants, auth.members);
            const { quantity, durationMinutes } = validateRecordMeasures(req, sourceInfo, body);
            const fieldValues = validateFieldValues(req, body.fieldValues, sourceInfo.fields, {
              requireActiveOptions: sourceInfo.requireActiveOptions
            });
            const note = validateNote(req, body.note);
            const saveAsTemplate = body.saveAsTemplate === true;
            if (saveAsTemplate && sourceInfo.kind !== "adHoc") {
              throw validationError(req, "仅临时记录可保存为模板", [
                fieldError("saveAsTemplate", "INVALID_OPTION", "模板记录不能再保存为模板")
              ]);
            }

            let createdTemplateId = null;
            let pinResult = null;
            if (saveAsTemplate) {
              const adHoc = body.adHoc;
              const [countRows] = await conn.execute(
                "SELECT COUNT(*) AS total FROM housework_templates WHERE space_id = ? AND status = 'active'",
                [spaceId]
              );
              if (Number(countRows[0].total) >= MAX_ACTIVE_TEMPLATES) {
                throw validationError(req, "模板数量已达上限", [
                  fieldError("saveAsTemplate", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_TEMPLATES} 个启用模板`)
                ]);
              }
              await ensureTemplateNameAvailable(req, conn, spaceId, sourceInfo.snapshot.category.categoryId, normalizeName(sourceInfo.snapshot.name));
              createdTemplateId = uuid();
              await conn.execute(
                `INSERT INTO housework_templates
                   (template_id, space_id, category_id, name, normalized_name, description, icon_json, color,
                    sort_order, measure_mode, unit, default_quantity, duration_enabled, default_duration_minutes,
                    weight, fields_json, status)
                 VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, 0, ?, ?, NULL, 0, NULL, ?, '[]', 'active')`,
                [
                  createdTemplateId,
                  spaceId,
                  sourceInfo.snapshot.category.categoryId,
                  sourceInfo.snapshot.name,
                  normalizeName(sourceInfo.snapshot.name),
                  sourceInfo.measureMode,
                  sourceInfo.unit,
                  sourceInfo.snapshot.weight
                ]
              );
              await writeConfigRevision(conn, {
                spaceId,
                entityType: "template",
                entityId: createdTemplateId,
                actorUserId: userId,
                action: "create",
                beforeVersion: null,
                afterVersion: 1,
                afterJson: { fromAdHocRecord: true }
              });

              // 成功后只给本人的常用偏好置顶（置顶满 12 提示 limit_reached）
              const [prefRows] = await conn.execute(
                "SELECT * FROM housework_preferences WHERE space_id = ? AND user_id = ? LIMIT 1 FOR UPDATE",
                [spaceId, userId]
              );
              const prefs = prefRows.length > 0 ? preferencesToApi(prefRows[0]) : defaultPreferences(spaceId, userId);
              if (prefs.pinnedTemplateIds.includes(createdTemplateId)) {
                pinResult = "pinned";
              } else if (prefs.pinnedTemplateIds.length >= MAX_PINNED) {
                pinResult = "limit_reached";
              } else {
                prefs.pinnedTemplateIds = [createdTemplateId, ...prefs.pinnedTemplateIds];
                pinResult = "pinned";
              }
              const newPrefVersion = prefs.version + (prefRows.length > 0 ? 1 : 0);
              await conn.execute(
                `INSERT INTO housework_preferences
                   (space_id, user_id, pinned_template_ids_json, hidden_template_ids_json, ordered_template_ids_json,
                    layout, display_aliases_json, default_performer_mode, version)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON DUPLICATE KEY UPDATE
                   pinned_template_ids_json = VALUES(pinned_template_ids_json),
                   version = VALUES(version)`,
                [
                  spaceId,
                  userId,
                  JSON.stringify(prefs.pinnedTemplateIds),
                  JSON.stringify(prefs.hiddenTemplateIds),
                  JSON.stringify(prefs.orderedTemplateIds),
                  prefs.layout,
                  JSON.stringify(prefs.displayAliases),
                  prefs.defaultPerformerMode,
                  newPrefVersion
                ]
              );
            }

            const recordId = uuid();
            await conn.execute(
              `INSERT INTO housework_records
                 (record_id, space_id, template_id, template_version, name, category_id_snapshot, snapshot_json,
                  completed_date, completed_time, quantity, duration_minutes, weight_snapshot,
                  field_values_json, note, created_by, updated_by, client_mutation_id, version)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)`,
              [
                recordId,
                spaceId,
                sourceInfo.templateId,
                sourceInfo.templateVersion,
                sourceInfo.snapshot.name,
                sourceInfo.snapshot.category.categoryId,
                JSON.stringify(sourceInfo.snapshot),
                completedDate,
                completedTime,
                quantity,
                durationMinutes,
                sourceInfo.snapshot.weight,
                JSON.stringify(fieldValues),
                note,
                userId,
                userId,
                mutationId
              ]
            );
            for (const participant of participants) {
              await conn.execute(
                `INSERT INTO housework_record_participants (record_id, user_id, share_bps, display_name_snapshot)
                 VALUES (?, ?, ?, ?)`,
                [recordId, participant.userId, participant.shareBps, participant.displayName]
              );
            }
            const recordRow = await loadRecordRow(conn, spaceId, recordId);
            const participantRows = await loadParticipantsByRecordIds(conn, [recordId]);
            const recordApi = recordToApi(recordRow, participantRows);
            await writeRecordRevision(conn, {
              recordId,
              spaceId,
              actorUserId: userId,
              action: "create",
              beforeVersion: null,
              afterVersion: 1,
              changedFields: null,
              beforeJson: null,
              afterJson: recordApi
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            const result = {
              operation: OPERATIONS.RECORDS_CREATE,
              entity: recordApi
            };
            if (createdTemplateId) {
              result.createdTemplateId = createdTemplateId;
              result.pinResult = pinResult;
            }
            return { resultId: recordId, result, revision };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/records/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      await withTransaction(pool, async (conn) => {
        await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const record = await loadRecordRow(conn, spaceId, id);
        if (!record) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "记录不存在");
        }
        const participants = await loadParticipantsByRecordIds(conn, [id]);
        res.json({ ok: true, data: recordToApi(record, participants) });
      });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/spaces/:spaceId/records/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      for (const key of RECORD_IMMUTABLE_KEYS) {
        if (body[key] !== undefined) {
          throw validationError(req, "包含不可修改的字段", [fieldError(key, "INVALID_OPTION", "该字段不允许修改")]);
        }
      }
      if (!RECORD_PATCHABLE_KEYS_PRESENT(body)) {
        throw validationError(req, "至少提供一项修改", [fieldError("note", "REQUIRED", "没有可更新的字段")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/spaces/:spaceId/records/:id",
        targetId: id,
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.RECORDS_UPDATE, mutationId, requestHash, async (conn, auth) => {
            const record = await loadRecordRow(conn, spaceId, id, { lock: true });
            if (!record) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "记录不存在");
            }
            if (record.deleted_at !== null && record.deleted_at !== undefined) {
              throw reject(req, 409, "STATE_CONFLICT", "记录已删除，不能编辑；可在回收站恢复");
            }
            const currentVersion = Number(record.version);
            const snapshot = parseJsonColumn(record.snapshot_json, {});
            const fields = snapshot.fields || [];
            const beforeApi = recordToApi(record, await loadParticipantsByRecordIds(conn, [id]));

            const changedFields = [];
            let newCompletedDate = record.completed_date;
            let newCompletedTime = record.completed_time;
            let newQuantity = dbDecimal(record.quantity);
            let newDuration = record.duration_minutes === null ? null : Number(record.duration_minutes);
            let newFieldValues = parseJsonColumn(record.field_values_json, {});
            let newNote = record.note;
            let newParticipants = null;

            if (body.completedDate !== undefined || body.completedTime !== undefined) {
              newCompletedDate = body.completedDate !== undefined
                ? parseCompletedDate(req, body.completedDate)
                : record.completed_date;
              newCompletedTime = body.completedTime !== undefined
                ? parseCompletedTime(req, body.completedTime, newCompletedDate)
                : (body.completedDate !== undefined
                    ? parseCompletedTime(req, record.completed_time, newCompletedDate)
                    : record.completed_time);
              changedFields.push("completedDate");
              if (body.completedTime !== undefined) changedFields.push("completedTime");
            }
            if (body.quantity !== undefined || body.durationMinutes !== undefined) {
              const sourceInfo = {
                measureMode: snapshot.measureMode || "event",
                durationEnabled: Boolean(snapshot.durationEnabled)
              };
              if (body.quantity !== undefined) {
                if (sourceInfo.measureMode === "event") {
                  newQuantity = "1.00";
                } else {
                  newQuantity = formatCents(parseDecimalCents(req, body.quantity, "quantity", { min: 1, max: 9999999 }));
                }
                changedFields.push("quantity");
              }
              if (body.durationMinutes !== undefined) {
                if (!sourceInfo.durationEnabled && body.durationMinutes !== null) {
                  throw validationError(req, "该记录未开启耗时", [
                    fieldError("durationMinutes", "INVALID_OPTION", "该记录快照未开启耗时统计，不能填写时长")
                  ]);
                }
                if (body.durationMinutes === null) {
                  newDuration = null;
                } else {
                  const minutes = typeof body.durationMinutes === "number" ? body.durationMinutes : Number(String(body.durationMinutes));
                  if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 1440) {
                    throw validationError(req, "耗时超出范围", [fieldError("durationMinutes", "OUT_OF_RANGE", "耗时必须是 1–1440 的整数分钟")]);
                  }
                  newDuration = minutes;
                }
                changedFields.push("durationMinutes");
              }
            }
            if (body.participants !== undefined) {
              newParticipants = validateParticipants(req, body.participants, auth.members);
              changedFields.push("participants");
            }
            if (body.fieldValues !== undefined) {
              newFieldValues = validateFieldValues(req, body.fieldValues, fields, { requireActiveOptions: false });
              changedFields.push("fieldValues");
            }
            if (body.note !== undefined) {
              newNote = validateNote(req, body.note);
              changedFields.push("note");
            }

            const [updateResult] = await conn.execute(
              `UPDATE housework_records
               SET completed_date = ?, completed_time = ?, quantity = ?, duration_minutes = ?,
                   field_values_json = ?, note = ?, updated_by = ?, version = version + 1
               WHERE record_id = ? AND space_id = ? AND version = ?`,
              [
                newCompletedDate,
                newCompletedTime,
                newQuantity,
                newDuration,
                JSON.stringify(newFieldValues),
                newNote,
                userId,
                id,
                spaceId,
                expectedVersion
              ]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "记录已被他人修改，请查看最新版本", {
                currentVersion: currentVersion,
                entityType: "record",
                entityId: id
              });
            }
            if (newParticipants) {
              await conn.execute("DELETE FROM housework_record_participants WHERE record_id = ?", [id]);
              for (const participant of newParticipants) {
                await conn.execute(
                  `INSERT INTO housework_record_participants (record_id, user_id, share_bps, display_name_snapshot)
                   VALUES (?, ?, ?, ?)`,
                  [id, participant.userId, participant.shareBps, participant.displayName]
                );
              }
            }

            const recordRow = await loadRecordRow(conn, spaceId, id);
            const participantRows = await loadParticipantsByRecordIds(conn, [id]);
            const recordApi = recordToApi(recordRow, participantRows);
            await writeRecordRevision(conn, {
              recordId: id,
              spaceId,
              actorUserId: userId,
              action: "update",
              beforeVersion: expectedVersion,
              afterVersion: expectedVersion + 1,
              changedFields,
              beforeJson: beforeApi,
              afterJson: recordApi
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: id,
              result: { operation: OPERATIONS.RECORDS_UPDATE, entity: recordApi },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  function RECORD_PATCHABLE_KEYS_PRESENT(body) {
    for (const key of RECORD_PATCHABLE) {
      if (body[key] !== undefined) return true;
    }
    return false;
  }

  router.delete("/spaces/:spaceId/records/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const mutationId = requireMutationId(req, req.query);
      const expectedVersion = parseExpectedVersion(req, req.query.expectedVersion);
      const requestHash = buildRequestHash({
        method: "DELETE",
        resourcePath: "/spaces/:spaceId/records/:id",
        targetId: id,
        expectedVersion,
        body: {}
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.RECORDS_DELETE, mutationId, requestHash, async (conn, auth) => {
            const record = await loadRecordRow(conn, spaceId, id, { lock: true });
            if (!record) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "记录不存在");
            }
            if (record.deleted_at !== null && record.deleted_at !== undefined) {
              throw reject(req, 409, "STATE_CONFLICT", "记录已删除，不能重复删除");
            }
            const currentVersion = Number(record.version);
            const beforeApi = recordToApi(record, await loadParticipantsByRecordIds(conn, [id]));
            const [updateResult] = await conn.execute(
              `UPDATE housework_records
               SET deleted_at = NOW(3), deleted_by = ?, updated_by = ?, version = version + 1
               WHERE record_id = ? AND space_id = ? AND version = ? AND deleted_at IS NULL`,
              [userId, userId, id, spaceId, expectedVersion]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "记录已被他人修改，请查看最新版本", {
                currentVersion: currentVersion,
                entityType: "record",
                entityId: id
              });
            }
            const recordRow = await loadRecordRow(conn, spaceId, id);
            const participantRows = await loadParticipantsByRecordIds(conn, [id]);
            const recordApi = recordToApi(recordRow, participantRows);
            await writeRecordRevision(conn, {
              recordId: id,
              spaceId,
              actorUserId: userId,
              action: "delete",
              beforeVersion: expectedVersion,
              afterVersion: expectedVersion + 1,
              changedFields: null,
              beforeJson: beforeApi,
              afterJson: recordApi
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: id,
              result: { operation: OPERATIONS.RECORDS_DELETE, entity: recordApi },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/spaces/:spaceId/records/:id/restore", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/spaces/:spaceId/records/:id/restore",
        targetId: id,
        expectedVersion,
        body
      });

      const outcome = await performWrite(req, spaceId, OPERATIONS.RECORDS_RESTORE, mutationId, requestHash, async (conn, auth) => {
            const record = await loadRecordRow(conn, spaceId, id, { lock: true });
            if (!record) {
              throw reject(req, 404, "RESOURCE_NOT_FOUND", "记录不存在");
            }
            if (record.deleted_at === null || record.deleted_at === undefined) {
              throw reject(req, 409, "STATE_CONFLICT", "记录未删除，不能恢复");
            }
            const currentVersion = Number(record.version);
            const beforeApi = recordToApi(record, await loadParticipantsByRecordIds(conn, [id]));
            const [updateResult] = await conn.execute(
              `UPDATE housework_records
               SET deleted_at = NULL, deleted_by = NULL, updated_by = ?, version = version + 1
               WHERE record_id = ? AND space_id = ? AND version = ? AND deleted_at IS NOT NULL`,
              [userId, id, spaceId, expectedVersion]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "VERSION_CONFLICT", "记录已被他人修改，请查看最新版本", {
                currentVersion: currentVersion,
                entityType: "record",
                entityId: id
              });
            }
            const recordRow = await loadRecordRow(conn, spaceId, id);
            const participantRows = await loadParticipantsByRecordIds(conn, [id]);
            const recordApi = recordToApi(recordRow, participantRows);
            await writeRecordRevision(conn, {
              recordId: id,
              spaceId,
              actorUserId: userId,
              action: "restore",
              beforeVersion: expectedVersion,
              afterVersion: expectedVersion + 1,
              changedFields: null,
              beforeJson: beforeApi,
              afterJson: recordApi
            });
            const revision = await bumpSpaceRevision(conn, spaceId);
            return {
              resultId: id,
              result: { operation: OPERATIONS.RECORDS_RESTORE, entity: recordApi },
              revision
            };
      });
      respondWrite(res, outcome.revision, outcome.replayed, outcome.result);
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/records/:id/revisions", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, id } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const data = await withTransaction(pool, async (conn) => {
        await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const record = await loadRecordRow(conn, spaceId, id);
        if (!record) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "记录不存在");
        }
        const [countRows] = await conn.execute(
          "SELECT COUNT(*) AS total FROM housework_record_revisions WHERE record_id = ?",
          [id]
        );
        let boundary = null;
        const normalized = { limit };
        if (req.query.cursor) {
          boundary = verifyCursor(req, cursorSecret, req.query.cursor, spaceId, record.revision, normalized, limit);
        }
        const continuation = boundary ? " AND id < ?" : "";
        const continuationParams = boundary ? [boundary.id] : [];
        const [rows] = await conn.execute(
          `SELECT id, record_id, space_id, actor_user_id, action, before_version, after_version,
                  changed_fields_json,
                  DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS created_at
           FROM housework_record_revisions
           WHERE record_id = ?${continuation}
           ORDER BY id DESC LIMIT ${limit + 1}`,
          [id, ...continuationParams]
        );
        const hasMore = rows.length > limit;
        const items = rows.slice(0, limit).map((row) => ({
          revisionId: String(row.id),
          recordId: row.record_id,
          actorUserId: String(row.actor_user_id),
          action: row.action,
          beforeVersion: row.before_version === null ? null : Number(row.before_version),
          afterVersion: Number(row.after_version),
          changedFields: parseJsonColumn(row.changed_fields_json, null),
          createdAt: isoFromShanghaiText(row.created_at)
        }));
        const nextCursor = hasMore
          ? buildCursor(cursorSecret, spaceId, record.revision, normalized, limit, { id: rows[limit - 1].id })
          : null;
        return { items, total: Number(countRows[0].total), nextCursor, revision: Number(record.revision) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 配置审计 / mutation 查询
   * ================================================================ */

  router.get("/spaces/:spaceId/config-revisions", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const entityType = req.query.entityType;
      if (entityType !== undefined && entityType !== null && entityType !== "" &&
          entityType !== "category" && entityType !== "template" && entityType !== "settings") {
        throw validationError(req, "entityType 无效", [fieldError("entityType", "INVALID_OPTION", "只能是 category/template/settings")]);
      }
      const entityId = typeof req.query.entityId === "string" && req.query.entityId ? req.query.entityId : null;
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const where = ["space_id = ?"];
        const params = [spaceId];
        if (entityType) {
          where.push("entity_type = ?");
          params.push(entityType);
        }
        if (entityId) {
          where.push("entity_id = ?");
          params.push(entityId);
        }
        const whereClause = where.join(" AND ");
        const [countRows] = await conn.execute(
          `SELECT COUNT(*) AS total FROM housework_config_revisions WHERE ${whereClause}`,
          params
        );
        const normalized = { entityType: entityType || null, entityId, limit };
        let boundary = null;
        if (req.query.cursor) {
          boundary = verifyCursor(req, cursorSecret, req.query.cursor, spaceId, auth.space.revision, normalized, limit);
        }
        const continuation = boundary ? " AND id < ?" : "";
        const continuationParams = boundary ? [boundary.id] : [];
        const [rows] = await conn.execute(
          `SELECT id, space_id, entity_type, entity_id, actor_user_id, action, before_version, after_version,
                  before_json, after_json,
                  DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f+08:00') AS created_at
           FROM housework_config_revisions
           WHERE ${whereClause}${continuation}
           ORDER BY id DESC LIMIT ${limit + 1}`,
          [...params, ...continuationParams]
        );
        const hasMore = rows.length > limit;
        const items = rows.slice(0, limit).map((row) => ({
          revisionId: String(row.id),
          spaceId: row.space_id,
          entityType: row.entity_type,
          entityId: row.entity_id,
          actorUserId: String(row.actor_user_id),
          action: row.action,
          beforeVersion: row.before_version === null ? null : Number(row.before_version),
          afterVersion: Number(row.after_version),
          beforeJson: parseJsonColumn(row.before_json, null),
          afterJson: parseJsonColumn(row.after_json, null),
          createdAt: isoFromShanghaiText(row.created_at)
        }));
        const nextCursor = hasMore
          ? buildCursor(cursorSecret, spaceId, auth.space.revision, normalized, limit, { id: rows[limit - 1].id })
          : null;
        return { items, total: Number(countRows[0].total), nextCursor, revision: Number(auth.space.revision) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.get("/spaces/:spaceId/mutations/:clientMutationId", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId, clientMutationId } = req.params;
      const operation = typeof req.query.operation === "string" && req.query.operation ? req.query.operation : null;
      await withTransaction(pool, async (conn) => {
        await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const params = [spaceId, userId, clientMutationId];
        let sql = `SELECT operation, result_id, result_json, created_at FROM housework_mutations
                   WHERE space_id = ? AND actor_user_id = ? AND client_mutation_id = ?`;
        if (operation) {
          sql += " AND operation = ?";
          params.push(operation);
        }
        sql += " ORDER BY id DESC LIMIT 1";
        const [rows] = await conn.execute(sql, params);
        if (rows.length === 0) {
          res.json({
            ok: true,
            data: { state: "not_found", operation, clientMutationId, result: null }
          });
          return;
        }
        res.json({
          ok: true,
          data: {
            state: "applied",
            operation: rows[0].operation,
            clientMutationId,
            result: parseJsonColumn(rows[0].result_json, null)
          }
        });
      });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 8. 统计（spec 7 / 8.6）
   * ================================================================ */

  function computeStatistics({ auth, records, participants, categories, templates, dateFrom, dateTo }) {
    const settings = auth.settings || {};
    const showWorkload = settings.showWorkloadStatistics === true;
    const showDuration = settings.showDurationStatistics === true;

    const categoryById = new Map(categories.map((c) => [c.category_id, c]));
    const templateById = new Map(templates.map((t) => [t.template_id, t]));
    const members = auth.members;
    if (members.length === 0 && auth.space.scope === "personal") {
      members.push({ user_id: auth.space.owner_user_id, display_name_snapshot: String(auth.space.owner_user_id) });
    }
    const memberIds = members.map((m) => Number(m.user_id));

    // per-user accumulators（BigInt 精确累加）
    const perUser = new Map();
    for (const id of memberIds) {
      perUser.set(id, {
        participationCount: 0,
        bpsSum: 0n,
        durationBpsSum: 0n,
        workloadProd: 0n
      });
    }

    const quantityGroups = new Map();
    const categoryGroups = new Map();
    const templateGroups = new Map();
    const adHocGroups = new Map();
    const daily = new Map();

    let durationTotalMinutes = 0;
    let durationCount = 0;
    let workloadProdTotal = 0n;
    let totalBps = 0n;

    const sortedRecords = [...records].sort((a, b) => {
      if (a.completed_date !== b.completed_date) return a.completed_date < b.completed_date ? -1 : 1;
      return a.record_id < b.record_id ? -1 : 1;
    });

    // 参与者按 record_id 预分组，避免对每条记录全表扫描 participants（O(N+M) 替代 O(N×M)）
    const participantsByRecord = new Map();
    for (const participant of participants) {
      const list = participantsByRecord.get(participant.record_id);
      if (list) list.push(participant);
      else participantsByRecord.set(participant.record_id, [participant]);
    }

    for (const record of sortedRecords) {
      const recordParticipants = participantsByRecord.get(record.record_id) || [];
      const qtyCents = BigInt(Math.round(Number(dbDecimal(record.quantity)) * 100));
      const weightCents = BigInt(Math.round(Number(dbDecimal(record.weight_snapshot)) * 100));
      const duration = record.duration_minutes === null ? null : Number(record.duration_minutes);
      if (duration !== null) {
        durationTotalMinutes += duration;
        durationCount += 1;
      }
      totalBps += 10000n;
      workloadProdTotal += weightCents * qtyCents;

      for (const participant of recordParticipants) {
        const userId = Number(participant.user_id);
        const bps = BigInt(participant.share_bps);
        const acc = perUser.get(userId);
        if (!acc) continue;
        acc.participationCount += 1;
        acc.bpsSum += bps;
        if (duration !== null) acc.durationBpsSum += bps * BigInt(duration);
        acc.workloadProd += weightCents * qtyCents * bps;
      }

      // quantity groups：template:t*:mode:unit；临时按归一化名称分组，单位不同绝不相加
      const snapshot = parseJsonColumn(record.snapshot_json, {});
      const measureMode = snapshot.measureMode || "event";
      const unit = snapshot.unit || "次";
      let groupKey;
      let adHocName = null;
      let displayName;
      let archivedFlag = false;
      if (record.template_id) {
        groupKey = `template:${record.template_id}:${measureMode}:${unit}`;
        const current = templateById.get(record.template_id);
        displayName = current ? current.name : (snapshot.name || record.name);
        archivedFlag = !current || current.status !== "active";
      } else {
        adHocName = normalizeName(snapshot.name || record.name);
        groupKey = `adhoc:${adHocName}:${measureMode}:${unit}`;
        displayName = snapshot.name || record.name;
      }
      let quantityGroup = quantityGroups.get(groupKey);
      if (!quantityGroup) {
        quantityGroup = {
          groupKey,
          templateId: record.template_id,
          adHocName,
          displayName,
          measureMode,
          unit,
          archived: archivedFlag,
          hasHistoricalNames: false,
          quantityCents: 0n,
          allocations: new Map()
        };
        quantityGroups.set(groupKey, quantityGroup);
      }
      quantityGroup.quantityCents += qtyCents;
      if (quantityGroup.displayName !== (snapshot.name || record.name)) quantityGroup.hasHistoricalNames = true;
      for (const participant of recordParticipants) {
        const userId = Number(participant.user_id);
        const bps = BigInt(participant.share_bps);
        quantityGroup.allocations.set(userId, (quantityGroup.allocations.get(userId) || 0n) + qtyCents * bps);
      }

      // category groups（按快照 categoryId 分组）
      const categoryId = record.category_id_snapshot;
      let categoryGroup = categoryGroups.get(categoryId);
      if (!categoryGroup) {
        const current = categoryById.get(categoryId);
        categoryGroup = {
          categoryId,
          displayName: current ? current.name : (snapshot.category && snapshot.category.name) || null,
          archived: !current || current.status !== "active",
          hasHistoricalNames: false,
          householdCount: 0
        };
        categoryGroups.set(categoryId, categoryGroup);
      }
      categoryGroup.householdCount += 1;
      const snapshotCategoryName = snapshot.category && snapshot.category.name;
      if (snapshotCategoryName && categoryGroup.displayName && snapshotCategoryName !== categoryGroup.displayName) {
        categoryGroup.hasHistoricalNames = true;
      }

      // template groups（只含真实模板）
      if (record.template_id) {
        let templateGroup = templateGroups.get(record.template_id);
        if (!templateGroup) {
          const current = templateById.get(record.template_id);
          templateGroup = {
            templateId: record.template_id,
            displayName: current ? current.name : (snapshot.name || record.name),
            archived: !current || current.status !== "active",
            hasHistoricalNames: false,
            householdCount: 0
          };
          templateGroups.set(record.template_id, templateGroup);
        }
        templateGroup.householdCount += 1;
        if ((snapshot.name || record.name) !== templateGroup.displayName) templateGroup.hasHistoricalNames = true;
      } else {
        const adHocKey = normalizeName(snapshot.name || record.name);
        let adHocGroup = adHocGroups.get(adHocKey);
        if (!adHocGroup) {
          adHocGroup = {
            groupKey: `adhoc:${adHocKey}`,
            adHocName: adHocKey,
            displayName: snapshot.name || record.name,
            householdCount: 0
          };
          adHocGroups.set(adHocKey, adHocGroup);
        }
        adHocGroup.householdCount += 1;
      }

      // daily（仅返有记录日期，升序）
      const date = record.completed_date;
      let day = daily.get(date);
      if (!day) {
        day = { date, householdCount: 0, durationMinutes: 0, hasDuration: false, workloadProd: 0n };
        daily.set(date, day);
      }
      day.householdCount += 1;
      if (duration !== null) {
        day.durationMinutes += duration;
        day.hasDuration = true;
      }
      day.workloadProd += weightCents * qtyCents;
    }

    const byDisplayName = (a, b) => String(a.displayName || "").localeCompare(String(b.displayName || ""), "zh-Hans-CN");

    return {
      householdCount: records.length,
      equivalentTotal: formatCents(divideRoundHalfUp(totalBps * 100n, 10000n)),
      durationTotalMinutes: showDuration ? formatCents(BigInt(durationTotalMinutes) * 100n) : null,
      durationCoverage: showDuration ? { count: durationCount, total: records.length } : null,
      workloadTotal: showWorkload ? formatCents(divideRoundHalfUp(workloadProdTotal, 100n)) : null,
      members: members.map((member) => {
        const id = Number(member.user_id);
        const acc = perUser.get(id) || { participationCount: 0, bpsSum: 0n, durationBpsSum: 0n, workloadProd: 0n };
        return {
          userId: String(id),
          displayName: member.display_name_snapshot,
          participationCount: acc.participationCount,
          equivalentCount: formatCents(divideRoundHalfUp(acc.bpsSum * 100n, 10000n)),
          allocatedDurationMinutes: showDuration ? formatCents(divideRoundHalfUp(acc.durationBpsSum, 100n)) : null,
          workload: showWorkload ? formatCents(divideRoundHalfUp(acc.workloadProd, 1000000n)) : null
        };
      }),
      quantityGroups: [...quantityGroups.values()].sort(byDisplayName).map((group) => ({
        groupKey: group.groupKey,
        templateId: group.templateId,
        adHocName: group.adHocName,
        displayName: group.displayName,
        measureMode: group.measureMode,
        unit: group.unit,
        archived: group.archived,
        hasHistoricalNames: group.hasHistoricalNames,
        quantity: formatCents(group.quantityCents),
        allocations: memberIds.map((id) => ({
          userId: String(id),
          quantity: formatCents(divideRoundHalfUp(group.allocations.get(id) || 0n, 10000n))
        }))
      })),
      categoryGroups: [...categoryGroups.values()].sort(byDisplayName).map((group) => ({
        categoryId: group.categoryId,
        displayName: group.displayName,
        archived: group.archived,
        hasHistoricalNames: group.hasHistoricalNames,
        householdCount: group.householdCount
      })),
      templateGroups: [...templateGroups.values()].sort(byDisplayName).map((group) => ({
        templateId: group.templateId,
        displayName: group.displayName,
        archived: group.archived,
        hasHistoricalNames: group.hasHistoricalNames,
        householdCount: group.householdCount
      })),
      adHocGroups: [...adHocGroups.values()].sort(byDisplayName).map((group) => ({
        groupKey: group.groupKey,
        adHocName: group.adHocName,
        displayName: group.displayName,
        householdCount: group.householdCount
      })),
      daily: [...daily.values()].sort((a, b) => (a.date < b.date ? -1 : 1)).map((day) => ({
        date: day.date,
        householdCount: day.householdCount,
        durationMinutes: showDuration ? (day.hasDuration ? formatCents(BigInt(day.durationMinutes) * 100n) : null) : null,
        workload: showWorkload ? formatCents(divideRoundHalfUp(day.workloadProd, 100n)) : null
      })),
      _dateFrom: dateFrom,
      _dateTo: dateTo
    };
  }

  router.get("/spaces/:spaceId/statistics", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { spaceId } = req.params;
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeSpace(req, conn, userId, spaceId, { forWrite: false });
        const filter = buildRecordFilter(req, req.query, { forStatistics: true });
        const { dateFrom, dateTo } = parseDateRange(req, req.query);
        const whereClause = `r.space_id = ? AND ${filter.whereClause}`;
        const params = [spaceId, ...filter.params];

        const [records] = await conn.execute(
          `SELECT ${STATS_RECORD_SELECT} FROM housework_records r WHERE ${whereClause}`,
          params
        );
        const [participants] = await conn.execute(
          `SELECT p.record_id, p.user_id, p.share_bps, p.display_name_snapshot
           FROM housework_record_participants p
           JOIN housework_records r ON r.record_id = p.record_id
           WHERE ${whereClause}`,
          params
        );
        const [categoryRows] = await conn.execute(
          "SELECT category_id, name, status FROM housework_categories WHERE space_id = ?",
          [spaceId]
        );
        const [templateRows] = await conn.execute(
          "SELECT template_id, name, status, measure_mode, unit FROM housework_templates WHERE space_id = ?",
          [spaceId]
        );

        const stats = computeStatistics({
          auth,
          records,
          participants,
          categories: categoryRows,
          templates: templateRows,
          dateFrom,
          dateTo
        });
        const time = nowContext();
        return {
          spaceId,
          revision: Number(auth.space.revision),
          timezone: TIMEZONE,
          dateFrom: stats._dateFrom,
          dateTo: stats._dateTo,
          householdCount: stats.householdCount,
          equivalentTotal: stats.equivalentTotal,
          durationTotalMinutes: stats.durationTotalMinutes,
          durationCoverage: stats.durationCoverage,
          workloadTotal: stats.workloadTotal,
          members: stats.members,
          quantityGroups: stats.quantityGroups,
          categoryGroups: stats.categoryGroups,
          templateGroups: stats.templateGroups,
          adHocGroups: stats.adHocGroups,
          daily: stats.daily,
          serverNow: time.serverNow,
          today: time.today
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createHouseworkRouter };
