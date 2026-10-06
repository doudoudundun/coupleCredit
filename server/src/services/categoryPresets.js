/**
 * 分类预设注册表（物资/账单）—— 后端唯一权威（spec 5.2：前端不得另写一套不同 key）。
 *
 * 结构：packs[]，每 pack 有 packKey / packVersion / domain / direction / categories[]，
 * 每分类有稳定 presetKey / name / icon({type:'iconKey'|'emoji', value}) / color / templates[]。
 *
 * 约定：
 *   - presetKey 一旦发布永不改名、永不复用；内容变化只能递增 packVersion。
 *   - bills.expense.full 完整包与小程序 utils/categoryIcon.js 的 20 个支出分类同名同序
 *     （单元测试直接 fs 读该文件交叉校验），保证旧客户端兼容。
 *   - iconKey 必须是前端 images/category/ 已有的本地图标键（文件名去扩展名），
 *     没有对应图标的用 emoji；禁止任意远程地址（spec 5.2）。
 *   - 模板只预填表单：单位/金额/告警线是建议值，库存数量与账单金额不属于导入结果；
 *     suggested_* 为 null 表示没有建议值，不能补 0。
 */

/** 分类名称 → 去重键：NFC 规范化 + 英文小写 + 连续空白折叠为一个空格（spec 6.1）。 */
function normalizeNameKey(value) {
  return String(value).normalize("NFC").trim().toLowerCase().replace(/\s+/g, " ");
}

const PACKS = [
  {
    packKey: "inventory.home",
    packVersion: 1,
    domain: "inventory",
    direction: null,
    name: "基础居家",
    categories: [
      {
        presetKey: "inventory.home.food",
        name: "食品",
        icon: { type: "iconKey", value: "food" },
        color: "#E6A23C",
        templates: []
      },
      {
        presetKey: "inventory.home.drink",
        name: "饮品",
        icon: { type: "iconKey", value: "drink" },
        color: "#4A90D9",
        templates: []
      },
      {
        presetKey: "inventory.home.care",
        name: "洗护",
        icon: { type: "emoji", value: "🧴" },
        color: "#7E57C2",
        templates: []
      },
      {
        presetKey: "inventory.home.cleaning",
        name: "清洁用品",
        icon: { type: "emoji", value: "🧽" },
        color: "#26A69A",
        templates: [
          {
            presetKey: "inventory.home.laundry_detergent",
            name: "洗衣液",
            icon: { type: "emoji", value: "🧴" },
            defaultUnit: "瓶",
            suggestedAlertLine: "1.00",
            remark: null
          },
          {
            presetKey: "inventory.home.garbage_bags",
            name: "垃圾袋",
            icon: { type: "emoji", value: "🗑️" },
            defaultUnit: "卷",
            suggestedAlertLine: "1.00",
            remark: null
          }
        ]
      },
      {
        presetKey: "inventory.home.paper",
        name: "纸品",
        icon: { type: "emoji", value: "🧻" },
        color: "#8D6E63",
        templates: [
          {
            presetKey: "inventory.home.tissues",
            name: "抽纸",
            icon: { type: "emoji", value: "🧻" },
            defaultUnit: "包",
            suggestedAlertLine: "2.00",
            remark: null
          }
        ]
      }
    ]
  },
  {
    packKey: "inventory.pets",
    packVersion: 1,
    domain: "inventory",
    direction: null,
    name: "宠物",
    categories: [
      {
        presetKey: "inventory.pets.food",
        name: "宠物主粮",
        icon: { type: "emoji", value: "🐾" },
        color: "#F2A154",
        templates: [
          {
            presetKey: "inventory.pets.cat_litter",
            name: "猫砂",
            icon: { type: "emoji", value: "🐱" },
            defaultUnit: "袋",
            suggestedAlertLine: "1.00",
            remark: null
          }
        ]
      },
      {
        presetKey: "inventory.pets.supplies",
        name: "宠物用品",
        icon: { type: "iconKey", value: "pet" },
        color: "#26A69A",
        templates: []
      }
    ]
  },
  {
    packKey: "inventory.tools",
    packVersion: 1,
    domain: "inventory",
    direction: null,
    name: "工具",
    categories: [
      {
        presetKey: "inventory.tools.tools",
        name: "工具",
        icon: { type: "emoji", value: "🔧" },
        color: "#8A8F99",
        templates: []
      },
      {
        presetKey: "inventory.tools.hardware",
        name: "五金耗材",
        icon: { type: "emoji", value: "🔩" },
        color: "#6D7B8D",
        templates: [
          {
            presetKey: "inventory.tools.light_bulb",
            name: "灯泡",
            icon: { type: "emoji", value: "💡" },
            defaultUnit: "个",
            suggestedAlertLine: "1.00",
            remark: null
          }
        ]
      }
    ]
  },
  {
    packKey: "bills.expense.basic",
    packVersion: 1,
    domain: "bills",
    direction: "expense",
    name: "支出简洁包",
    categories: [
      {
        presetKey: "bills.expense.basic.dining",
        name: "餐饮",
        icon: { type: "iconKey", value: "food" },
        color: "#FF6B81",
        templates: []
      },
      {
        presetKey: "bills.expense.basic.shopping",
        name: "购物",
        icon: { type: "iconKey", value: "shopping" },
        color: "#E6A23C",
        templates: []
      },
      {
        presetKey: "bills.expense.basic.transport",
        name: "交通",
        icon: { type: "iconKey", value: "transport" },
        color: "#4A90D9",
        templates: [
          {
            presetKey: "bills.expense.basic.commute",
            name: "通勤",
            icon: { type: "iconKey", value: "transport" },
            note: null,
            suggestedAmount: null
          }
        ]
      },
      {
        presetKey: "bills.expense.basic.housing",
        name: "居住",
        icon: { type: "iconKey", value: "hotel" },
        color: "#7E57C2",
        templates: [
          {
            presetKey: "bills.expense.basic.rent",
            name: "房租",
            icon: { type: "iconKey", value: "hotel" },
            note: null,
            suggestedAmount: null
          }
        ]
      },
      {
        presetKey: "bills.expense.basic.health",
        name: "健康",
        icon: { type: "iconKey", value: "medical" },
        color: "#26A69A",
        templates: []
      },
      {
        presetKey: "bills.expense.basic.entertainment",
        name: "娱乐",
        icon: { type: "iconKey", value: "entertainment" },
        color: "#F2709C",
        templates: []
      },
      {
        presetKey: "bills.expense.basic.other",
        name: "其他",
        icon: { type: "iconKey", value: "other" },
        color: "#8A8F99",
        templates: []
      }
    ]
  },
  {
    // 与小程序 utils/categoryIcon.js 的 EXPENSE_CATEGORIES 同名同序（20 类），
    // 供旧客户端兼容导入；单测直接读该文件交叉校验。
    packKey: "bills.expense.full",
    packVersion: 1,
    domain: "bills",
    direction: "expense",
    name: "支出完整包",
    categories: [
      { presetKey: "bills.expense.full.food", name: "餐品", icon: { type: "iconKey", value: "food" }, color: "#FF6B81", templates: [] },
      { presetKey: "bills.expense.full.drink", name: "饮品", icon: { type: "iconKey", value: "drink" }, color: "#4A90D9", templates: [] },
      { presetKey: "bills.expense.full.fruit", name: "水果", icon: { type: "iconKey", value: "fruit" }, color: "#67C23A", templates: [] },
      { presetKey: "bills.expense.full.shopping", name: "购物", icon: { type: "iconKey", value: "shopping" }, color: "#E6A23C", templates: [] },
      { presetKey: "bills.expense.full.transport", name: "交通", icon: { type: "iconKey", value: "transport" }, color: "#4A90D9", templates: [] },
      { presetKey: "bills.expense.full.hotel", name: "住宿", icon: { type: "iconKey", value: "hotel" }, color: "#7E57C2", templates: [] },
      { presetKey: "bills.expense.full.entertainment", name: "娱乐", icon: { type: "iconKey", value: "entertainment" }, color: "#F2709C", templates: [] },
      { presetKey: "bills.expense.full.study", name: "学习", icon: { type: "iconKey", value: "study" }, color: "#5C6BC0", templates: [] },
      { presetKey: "bills.expense.full.medical", name: "医疗", icon: { type: "iconKey", value: "medical" }, color: "#26A69A", templates: [] },
      { presetKey: "bills.expense.full.daily", name: "日常", icon: { type: "iconKey", value: "daily" }, color: "#8D6E63", templates: [] },
      { presetKey: "bills.expense.full.travel", name: "旅游", icon: { type: "iconKey", value: "travel" }, color: "#29B6F6", templates: [] },
      { presetKey: "bills.expense.full.communication", name: "通讯", icon: { type: "iconKey", value: "communication" }, color: "#78909C", templates: [] },
      { presetKey: "bills.expense.full.social", name: "人情", icon: { type: "iconKey", value: "social" }, color: "#EC407A", templates: [] },
      { presetKey: "bills.expense.full.cosmetic", name: "化妆", icon: { type: "iconKey", value: "cosmetic" }, color: "#AB47BC", templates: [] },
      { presetKey: "bills.expense.full.member", name: "会员", icon: { type: "iconKey", value: "member" }, color: "#FFA726", templates: [] },
      { presetKey: "bills.expense.full.investment", name: "投资", icon: { type: "iconKey", value: "investment" }, color: "#66BB6A", templates: [] },
      { presetKey: "bills.expense.full.parenting", name: "亲子", icon: { type: "iconKey", value: "parenting" }, color: "#FFCA28", templates: [] },
      { presetKey: "bills.expense.full.pet", name: "宠物", icon: { type: "iconKey", value: "pet" }, color: "#26A69A", templates: [] },
      { presetKey: "bills.expense.full.decoration", name: "装修", icon: { type: "iconKey", value: "decoration" }, color: "#8D6E63", templates: [] },
      { presetKey: "bills.expense.full.other", name: "其他", icon: { type: "iconKey", value: "other" }, color: "#8A8F99", templates: [] }
    ]
  },
  {
    packKey: "bills.income.basic",
    packVersion: 1,
    domain: "bills",
    direction: "income",
    name: "收入基础包",
    categories: [
      {
        presetKey: "bills.income.basic.salary",
        name: "工资",
        icon: { type: "iconKey", value: "salary" },
        color: "#67C23A",
        templates: [
          {
            presetKey: "bills.income.basic.salary_pay",
            name: "工资",
            icon: { type: "iconKey", value: "salary" },
            note: null,
            suggestedAmount: null
          }
        ]
      },
      { presetKey: "bills.income.basic.parttime", name: "兼职", icon: { type: "iconKey", value: "parttime" }, color: "#4A90D9", templates: [] },
      { presetKey: "bills.income.basic.cashgift", name: "礼金", icon: { type: "iconKey", value: "cashgift" }, color: "#EC407A", templates: [] },
      { presetKey: "bills.income.basic.financial", name: "理财", icon: { type: "iconKey", value: "financial" }, color: "#FFA726", templates: [] },
      { presetKey: "bills.income.basic.other", name: "其他", icon: { type: "iconKey", value: "other" }, color: "#8A8F99", templates: [] }
    ]
  }
];

/* ------------------------------------------------------------------ *
 * 注册表索引与完整性自检（模块加载即失败，不让坏注册表进运行态）
 * ------------------------------------------------------------------ */

const packByKey = new Map();
const categoryByPresetKey = new Map();
const templateByPresetKey = new Map();

for (const pack of PACKS) {
  if (packByKey.has(pack.packKey)) {
    throw new Error(`categoryPresets: duplicate packKey ${pack.packKey}`);
  }
  if (!Number.isSafeInteger(pack.packVersion) || pack.packVersion < 1) {
    throw new Error(`categoryPresets: ${pack.packKey} packVersion 必须是正整数`);
  }
  if (pack.domain !== "inventory" && pack.domain !== "bills") {
    throw new Error(`categoryPresets: ${pack.packKey} domain 非法`);
  }
  if (pack.domain === "bills" && pack.direction !== "expense" && pack.direction !== "income") {
    throw new Error(`categoryPresets: ${pack.packKey} 账单包必须有 direction`);
  }
  if (pack.domain === "inventory" && pack.direction !== null) {
    throw new Error(`categoryPresets: ${pack.packKey} 物资包 direction 必须为 null`);
  }
  packByKey.set(pack.packKey, pack);
  const nameKeys = new Set();
  for (const category of pack.categories) {
    if (categoryByPresetKey.has(category.presetKey)) {
      throw new Error(`categoryPresets: duplicate category presetKey ${category.presetKey}`);
    }
    const nameKey = normalizeNameKey(category.name);
    if (nameKeys.has(nameKey)) {
      throw new Error(`categoryPresets: ${pack.packKey} 内分类 nameKey 重复 ${category.name}`);
    }
    nameKeys.add(nameKey);
    categoryByPresetKey.set(category.presetKey, { pack, category });
    for (const template of category.templates) {
      if (templateByPresetKey.has(template.presetKey)) {
        throw new Error(`categoryPresets: duplicate template presetKey ${template.presetKey}`);
      }
      templateByPresetKey.set(template.presetKey, { pack, category, template });
    }
  }
}

/** 按 domain/direction 过滤出适用包（direction 仅对 bills 有意义）。 */
function listPacks({ domain, direction = null }) {
  return PACKS.filter((pack) => {
    if (pack.domain !== domain) return false;
    if (domain === "bills") return pack.direction === direction;
    return true;
  });
}

function getCategoryPreset(presetKey) {
  return categoryByPresetKey.get(presetKey) || null;
}

function getTemplatePreset(presetKey) {
  return templateByPresetKey.get(presetKey) || null;
}

function getPack(packKey) {
  return packByKey.get(packKey) || null;
}

module.exports = {
  PACKS,
  normalizeNameKey,
  listPacks,
  getCategoryPreset,
  getTemplatePreset,
  getPack
};
