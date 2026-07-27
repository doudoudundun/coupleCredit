const { Router } = require("express");
const { loadActiveRelationship } = require("../utils/queryHelpers");

const SYSTEM_PROMPT = `你是一个信息提取助手。分析以下情侣聊天记录，从中提取可以自动记录的账单、物资或待办信息。

提取规则：
1. 账单(bill)：包含消费金额的对话，如"午饭花了35"、"打车20块"、"买了水果花了58"
   - title: 简短描述(如"午饭"、"打车"、"水果")
   - amount: 数字金额
   - type: 类别，从以下选择：餐饮、交通、停车、购物、娱乐、居住、医疗、教育、通讯、日用、服饰、美容、运动、社交、旅行、宠物、礼物、其他
   - date: 日期，格式 YYYY-MM-DD，如果提到"今天"用今天的日期，"昨天"用昨天的日期
   - incomeType: 0=支出，1=收入
   - owner: 谁付的钱，"自己"或"对方"或"共同"，如果无法判断默认"自己"

2. 物资(inventory)：提到购买或拥有的物品，如"买了两盒牛奶"、"家里还有大米"
   - name: 物品名称
   - quantity: 数量(数字)
   - unit: 单位(如"个"、"盒"、"袋"、"瓶")
   - category: 类别，从以下选择：食品、饮品、水果蔬菜、日用、厨具、清洁、医药、其他

3. 待办(todo)：提到需要做的事情，如"明天记得交电费"、"周末去超市"
   - title: 待办事项标题
   - priority: 优先级，从 low/medium/high 选择
   - fuzzyDateText: 时间描述(如"明天"、"本周五"、"尽快")

注意事项：
- 只提取明确的信息，不要过度推测
- 没有可提取内容时返回空数组
- 同一条消息可能同时匹配多种类型，例如"买了牙膏12块"应同时提取账单和物资，请分别输出
- 每种类型每条消息最多提取一条记录
- 返回纯JSON，不要有额外文字`;

function createAiChatRouter({ pool }) {
  const router = Router();

  router.post("/analyze", async (req, res, next) => {
    try {
      const { messages } = req.body;
      const userId = req.userId;
      if (!userId) return res.status(400).json({ ok: false, error: { code: "MISSING_USER_ID", message: "缺少用户ID" } });
      if (!Array.isArray(messages) || messages.length === 0) {
        return res.json({ ok: true, data: { extractions: [] } });
      }

      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) return res.status(403).json({ ok: false, error: { code: "NO_RELATIONSHIP", message: "未绑定情侣关系" } });

      const apiKey = process.env.AI_API_KEY;
      const baseUrl = process.env.AI_BASE_URL || "https://open.bigmodel.cn/api/paas/v4";
      if (!apiKey) return res.json({ ok: true, data: { extractions: [] } });

      const chatText = messages.map(m => `${m.username || "用户"}: ${m.content}`).join("\n");
      const today = new Date().toISOString().split("T")[0];

      const apiUrl = resolveApiUrl(baseUrl);
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      let aiResponse;
      try {
        const fetchRes = await fetch(apiUrl, {
          method: "POST",
          headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey}` },
          body: JSON.stringify({
            model: process.env.AI_CHAT_MODEL || process.env.AI_MODEL || "glm-4-flash",
            messages: [
              { role: "system", content: SYSTEM_PROMPT + `\n\n今天是 ${today}。` },
              { role: "user", content: chatText }
            ],
            temperature: 0.1,
            max_tokens: 1000
          }),
          signal: controller.signal
        });
        const raw = await fetchRes.text();
        clearTimeout(timeoutId);
        if (!fetchRes.ok) throw new Error(`AI API ${fetchRes.status}: ${raw.substring(0, 200)}`);
        aiResponse = JSON.parse(raw);
      } catch (err) {
        clearTimeout(timeoutId);
        console.error("[ai-chat/analyze] AI call failed:", err.message);
        return res.json({ ok: true, data: { extractions: [] } });
      }

      let extractions = [];
      try {
        const content = aiResponse.choices?.[0]?.message?.content || "";
        const jsonMatch = content.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
          const parsed = JSON.parse(jsonMatch[0]);
          if (Array.isArray(parsed.extractions)) {
            extractions = parsed.extractions;
          } else {
            for (const type of ["bill", "inventory", "todo"]) {
              if (Array.isArray(parsed[type])) {
                for (const item of parsed[type]) {
                  extractions.push({ type, data: item });
                }
              }
            }
          }
        }
      } catch (parseErr) {
        console.error("[ai-chat/analyze] Parse failed:", parseErr.message);
      }
      console.log("[ai-chat/analyze] Found", extractions.length, "extraction(s)");

      const savedExtractions = [];
      for (const ext of extractions) {
        if (!ext.type || !ext.data) continue;
        if (!["bill", "inventory", "todo"].includes(ext.type)) continue;

        const triggerMsgId = messages.length > 0 ? messages[messages.length - 1].id : null;
        const [result] = await pool.execute(
          `INSERT INTO ai_extraction_results (relationship_id, trigger_message_id, result_type, raw_data) VALUES (?, ?, ?, ?)`,
          [rel.relationship_id, triggerMsgId || null, ext.type, JSON.stringify(ext.data)]
        );
        savedExtractions.push({
          id: result.insertId,
          type: ext.type,
          data: ext.data,
          status: "pending"
        });
      }

      res.json({ ok: true, data: { extractions: savedExtractions } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/extractions", async (req, res, next) => {
    try {
      const userId = req.userId;
      if (!userId) return res.status(400).json({ ok: false, error: { code: "MISSING_USER_ID", message: "缺少用户ID" } });
      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) return res.status(403).json({ ok: false, error: { code: "NO_RELATIONSHIP", message: "未绑定情侣关系" } });

      const [rows] = await pool.execute(
        `SELECT id, result_type, raw_data, status, target_id, created_at FROM ai_extraction_results WHERE relationship_id = ? AND status = 'pending' ORDER BY created_at DESC LIMIT 20`,
        [rel.relationship_id]
      );
      const extractions = rows.map(r => ({
        id: r.id,
        type: r.result_type,
        data: typeof r.raw_data === "string" ? JSON.parse(r.raw_data) : r.raw_data,
        status: r.status,
        targetId: r.target_id,
        createdAt: r.created_at
      }));
      res.json({ ok: true, data: { extractions } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/extractions/:id/confirm", async (req, res, next) => {
    try {
      const extractionId = parseInt(req.params.id, 10);
      const { overrides } = req.body;
      const userId = req.userId;
      if (!userId) return res.status(400).json({ ok: false, error: { code: "MISSING_USER_ID", message: "缺少用户ID" } });
      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) return res.status(403).json({ ok: false, error: { code: "NO_RELATIONSHIP", message: "未绑定情侣关系" } });

      const [rows] = await pool.execute(
        `SELECT * FROM ai_extraction_results WHERE id = ? AND relationship_id = ? AND status = 'pending'`,
        [extractionId, rel.relationship_id]
      );
      if (rows.length === 0) return res.status(404).json({ ok: false, error: { code: "NOT_FOUND", message: "提取记录不存在" } });

      const extraction = rows[0];
      const data = overrides || (typeof extraction.raw_data === "string" ? JSON.parse(extraction.raw_data) : extraction.raw_data);
      let targetId = null;

      const isUser1 = rel.user_id_1 === userId;

      if (extraction.result_type === "bill") {
        let owner = 3;
        if (data.owner === "自己") owner = isUser1 ? 1 : 2;
        else if (data.owner === "对方") owner = isUser1 ? 2 : 1;
        const [result] = await pool.execute(
          `INSERT INTO bills (relationship_id, user_id, owner, title, type, amount, date, time, income_type) VALUES (?, ?, ?, ?, ?, ?, ?, CURTIME(), ?)`,
          [rel.relationship_id, userId, owner, data.title || "未命名", data.type || "其他", data.amount || 0, data.date || new Date().toISOString().split("T")[0], data.incomeType || 0]
        );
        targetId = result.insertId;
      } else if (extraction.result_type === "inventory") {
        const [existing] = await pool.execute(
          `SELECT inventory_id, quantity FROM inventory WHERE user_id = ? AND name = ? LIMIT 1`,
          [userId, data.name]
        );
        if (existing.length > 0) {
          await pool.execute(
            `UPDATE inventory SET quantity = quantity + ?, updated_at = NOW() WHERE inventory_id = ?`,
            [data.quantity || 1, existing[0].inventory_id]
          );
          targetId = existing[0].inventory_id;
        } else {
          const [result] = await pool.execute(
            `INSERT INTO inventory (user_id, relationship_id, name, category, quantity, unit) VALUES (?, ?, ?, ?, ?, ?)`,
            [userId, rel.relationship_id, data.name, data.category || "其他", data.quantity || 1, data.unit || "个"]
          );
          targetId = result.insertId;
        }
      } else if (extraction.result_type === "todo") {
        const [result] = await pool.execute(
          `INSERT INTO todo_items (user_id, relationship_id, title, priority, fuzzy_date_text) VALUES (?, ?, ?, ?, ?)`,
          [userId, rel.relationship_id, data.title || "新待办", data.priority || "medium", data.fuzzyDateText || null]
        );
        targetId = result.insertId;
      }

      await pool.execute(
        `UPDATE ai_extraction_results SET status = 'confirmed', target_id = ?, updated_at = NOW() WHERE id = ?`,
        [targetId, extractionId]
      );

      res.json({ ok: true, data: { targetId, type: extraction.result_type } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/extractions/:id/dismiss", async (req, res, next) => {
    try {
      const extractionId = parseInt(req.params.id, 10);
      const userId = req.userId;
      if (!userId) return res.status(400).json({ ok: false, error: { code: "MISSING_USER_ID", message: "缺少用户ID" } });
      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) return res.status(403).json({ ok: false, error: { code: "NO_RELATIONSHIP", message: "未绑定情侣关系" } });

      await pool.execute(
        `UPDATE ai_extraction_results SET status = 'dismissed', updated_at = NOW() WHERE id = ? AND relationship_id = ? AND status = 'pending'`,
        [extractionId, rel.relationship_id]
      );
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

function resolveApiUrl(baseUrl) {
  const trimmed = String(baseUrl || "").replace(/\/$/, "");
  if (trimmed.endsWith("/chat/completions")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/chat/completions`;
  return `${trimmed}/v1/chat/completions`;
}

module.exports = { createAiChatRouter };
