/**
 * bills / inventory 写入路径的 categoryId 校验助手 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md
 * （小程序仓库）第 3、9 节。
 *
 * 不变量：
 *   - categoryId 必须指向「记录所属范围」的可写集合中的启用分类：
 *     个人记录（relationship_id 为 NULL）→ 本人个人集合；
 *     关系记录 → 该关系的共享集合，且关系仍有效；
 *   - domain 必须匹配（bills/inventory），bills 还须匹配收支方向（expense/income）；
 *   - 不跨范围借用同名分类（spec 3：不得用一个同名分类覆盖两个范围）；
 *   - 归档分类拒绝新引用（spec 6.2：切换分类时只能选启用项）；
 *   - legacy 字符串提交（无 categoryId）完全不经过这里，行为与接入前一致。
 */
const { ApiError } = require("../errors");

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isUuid(value) {
  return typeof value === "string" && UUID_RE.test(value);
}

/**
 * 校验并加载调用者可写的启用分类。db 可为 pool 或事务连接。
 *
 * @param {object} args
 * @param {number|string} args.userId 调用者（JWT）
 * @param {number|null} args.relationshipId 记录所属关系；个人记录传 null
 * @param {"bills"|"inventory"} args.domain
 * @param {"expense"|"income"|null} args.direction 账单收支方向；物资传 null
 * @param {string} args.categoryId 客户端提交的分类 UUID
 * @returns 分类行 { id, name, icon_type, icon_value, color }
 */
async function resolveWritableCategory(db, { userId, relationshipId, domain, direction, categoryId }) {
  if (!isUuid(categoryId)) {
    throw new ApiError(400, "INVALID_REQUEST", "categoryId 必须是 UUID 字符串");
  }
  const [rows] = await db.execute(
    `SELECT ic.id, ic.name, ic.icon_type, ic.icon_value, ic.color, ic.status,
            c.domain, c.direction, c.scope, c.owner_user_id,
            c.relationship_id AS collection_relationship_id, c.status AS collection_status,
            r.status AS relationship_status
     FROM item_categories ic
     JOIN category_collections c ON c.id = ic.collection_id
     LEFT JOIN couple_relationships r ON r.relationship_id = c.relationship_id
     WHERE ic.id = ? LIMIT 1`,
    [categoryId]
  );
  const category = rows[0];
  const invalid = () => new ApiError(400, "INVALID_CATEGORY", "分类不存在或不属于当前写入范围");
  if (!category) throw invalid();
  if (category.domain !== domain) throw invalid();
  if (direction === null || direction === undefined) {
    if (category.direction !== null) throw invalid();
  } else if (category.direction !== direction) {
    throw invalid();
  }
  if (category.collection_status !== "active") throw invalid();
  if (relationshipId === null || relationshipId === undefined) {
    if (category.scope !== "personal" || Number(category.owner_user_id) !== Number(userId)) {
      throw invalid();
    }
  } else {
    if (category.scope !== "couple" || Number(category.collection_relationship_id) !== Number(relationshipId)) {
      throw invalid();
    }
    if (category.relationship_status !== "active") {
      throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系已失效，该共享集合禁止编辑");
    }
  }
  if (category.status !== "active") {
    throw new ApiError(409, "CATEGORY_ARCHIVED", "分类已归档，不能用于新数据");
  }
  return category;
}

module.exports = { isUuid, resolveWritableCategory };
