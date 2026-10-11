/**
 * 周期记账的排期计算与懒物化（F1）。
 *
 * 日期一律 'YYYY-MM-DD' 字符串，换算锚定 UTC（与服务器时区无关，纯日历运算）；
 * 「今天」按 Asia/Shanghai 口径（与 goals 的 todayInTimezone 一致）。
 * day_of_week 0-6 = 周日…周六（JS Date#getDay 同序）。
 */
const { loadActiveRelationship } = require("./queryHelpers");
const { withTransaction } = require("./transactions");
const { todayInTimezone } = require("./spaceContext");

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function isDateString(value) {
  return typeof value === "string" && DATE_RE.test(value);
}

/** 'YYYY-MM-DD' → UTC 锚定的 Date（getUTCDate/getUTCDay 等做纯日历运算）。 */
function toDate(value) {
  return new Date(`${value}T00:00:00Z`);
}

function formatDate(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

function todayDateString() {
  return todayInTimezone("Asia/Shanghai");
}

function daysInMonth(year, monthIndex) {
  return new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
}

/** monthly 的 day_of_month 截到目标月月末（1-31 → 2 月自动 28/29，4/6/9/11 月 30）。 */
function clampToMonth(year, monthIndex, dayOfMonth) {
  return Math.min(Math.max(dayOfMonth, 1), daysInMonth(year, monthIndex));
}

/**
 * 推进一个期次：monthly = 下月同号（短月截到月末），weekly = +7 天。
 * 与 firstOccurrenceOnOrAfter 共同保证序列不回退、不重复。
 */
function advanceDueDate(frequency, dayOfMonth, dayOfWeek, dueOn) {
  const date = toDate(dueOn);
  if (frequency === "weekly") {
    date.setUTCDate(date.getUTCDate() + 7);
    return formatDate(date);
  }
  const nextMonth = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1));
  return formatDate(new Date(Date.UTC(
    nextMonth.getUTCFullYear(),
    nextMonth.getUTCMonth(),
    clampToMonth(nextMonth.getUTCFullYear(), nextMonth.getUTCMonth(), dayOfMonth)
  )));
}

/** 从 fromOn（含当天）起按频率推第一个期次；缺省 nextDueOn 用。 */
function firstOccurrenceOnOrAfter(frequency, dayOfMonth, dayOfWeek, fromOn) {
  if (frequency === "weekly") {
    const date = toDate(fromOn);
    const delta = (dayOfWeek - date.getUTCDay() + 7) % 7;
    date.setUTCDate(date.getUTCDate() + delta);
    return formatDate(date);
  }
  const date = toDate(fromOn);
  const candidate = formatDate(new Date(Date.UTC(
    date.getUTCFullYear(),
    date.getUTCMonth(),
    clampToMonth(date.getUTCFullYear(), date.getUTCMonth(), dayOfMonth)
  )));
  return candidate >= fromOn ? candidate : advanceDueDate("monthly", dayOfMonth, null, candidate);
}

// 单模板单次物化最多补 5 年的月期次 / 5 年的周期次，防脏数据死循环。
const MAX_CATCH_UP_PERIODS = 60;

/**
 * 懒物化：把当前用户可见的 active 模板中 next_due_on <= today 的期次逐条补成
 * pending 实例（UNIQUE(template_id, due_on) 兜底防重），并推进 next_due_on。
 *
 * 事务内 SELECT ... FOR UPDATE 按 id 序锁模板行：伴侣双方同时触发时后者阻塞、
 * 重读已推进的 next_due_on 后空转返回，不会重复生成。返回本次新建实例数。
 * 调用方已解析过 active 关系时可通过 options.relationship 传入，避免重复查询。
 */
async function materializeRecurringTemplates(pool, userId, { relationship } = {}) {
  const active = relationship !== undefined
    ? relationship
    : await loadActiveRelationship(pool, userId);
  const relationshipId = active ? active.relationship_id : null;
  const today = todayDateString();

  return withTransaction(pool, async (conn) => {
    // relationship_id = NULL 永不命中，无关系时只剩私人模板分支
    const [templates] = await conn.execute(
      `SELECT id, frequency, day_of_month, day_of_week, DATE_FORMAT(next_due_on, '%Y-%m-%d') AS next_due_on
       FROM recurring_bill_templates
       WHERE status = 'active' AND next_due_on <= ?
         AND ((relationship_id IS NULL AND user_id = ?) OR (relationship_id = ?))
       ORDER BY id
       FOR UPDATE`,
      [today, userId, relationshipId]
    );

    let created = 0;
    for (const template of templates) {
      let due = template.next_due_on;
      let periods = 0;
      while (due <= today && periods < MAX_CATCH_UP_PERIODS) {
        // ON DUPLICATE KEY 无实质更新：新插入 affectedRows=1，已存在=0（不计数）
        const [result] = await conn.execute(
          `INSERT INTO recurring_bill_instances (template_id, due_on)
           VALUES (?, ?) ON DUPLICATE KEY UPDATE template_id = template_id`,
          [template.id, due]
        );
        if (result.affectedRows === 1) created += 1;
        due = advanceDueDate(template.frequency, template.day_of_month, template.day_of_week, due);
        periods += 1;
      }
      if (due !== template.next_due_on) {
        await conn.execute(
          "UPDATE recurring_bill_templates SET next_due_on = ? WHERE id = ?",
          [due, template.id]
        );
      }
    }
    return created;
  });
}

module.exports = {
  isDateString,
  todayDateString,
  firstOccurrenceOnOrAfter,
  advanceDueDate,
  materializeRecurringTemplates
};
