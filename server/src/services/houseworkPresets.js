/**
 * 家务内置推荐包注册表（代码常量管理，spec: docs/HOUSEWORK_RECORDS_SPEC_20261001.md 5.4 / 决策 8）。
 *
 * 从 routes/housework.js 抽出：推荐导入路由与基础包播撒（services/houseworkSeed.js）
 * 共用同一份注册表，避免两处漂移。presetKey / presetCategoryKey 是稳定身份，
 * 只增不改；key 一旦发布过就视为已被引用（唯一约束覆盖归档墓碑）。
 */

const PRESET_PACKS = [
  {
    presetCategoryKey: "cleaning",
    name: "清洁",
    color: "#4A90D9",
    icon: { type: "iconKey", value: "clean" },
    templates: [
      { presetKey: "cleaning.sweep", name: "扫地", measureMode: "event", weight: "1.00", description: "清扫地面灰尘" },
      { presetKey: "cleaning.mop", name: "拖地", measureMode: "event", weight: "1.50" },
      { presetKey: "cleaning.vacuum", name: "客厅吸尘", measureMode: "event", weight: "1.00" }
    ]
  },
  {
    presetCategoryKey: "kitchen",
    name: "餐厨",
    color: "#E6A23C",
    icon: { type: "iconKey", value: "kitchen" },
    templates: [
      { presetKey: "kitchen.cook", name: "做饭", measureMode: "event", weight: "2.00" },
      { presetKey: "kitchen.dishes", name: "洗碗", measureMode: "event", weight: "1.00" }
    ]
  },
  {
    presetCategoryKey: "laundry",
    name: "洗护",
    color: "#7E57C2",
    icon: { type: "iconKey", value: "laundry" },
    templates: [
      { presetKey: "laundry.wash", name: "洗衣", measureMode: "quantity", unit: "件", weight: "0.50", durationEnabled: true, description: "按衣物件数计量" },
      { presetKey: "laundry.bedding", name: "换洗床品", measureMode: "event", weight: "2.00" }
    ]
  },
  {
    presetCategoryKey: "pets",
    name: "宠物",
    color: "#26A69A",
    icon: { type: "iconKey", value: "pets" },
    templates: [
      { presetKey: "pets.feed", name: "宠物喂食", measureMode: "event", weight: "0.50" },
      {
        presetKey: "pets.fish_tank",
        name: "清洗鱼缸",
        measureMode: "quantity",
        unit: "升",
        weight: "1.50",
        fields: [
          {
            fieldId: "9f0b1c26-7d42-4f00-9a10-1234567890a1",
            label: "区域",
            type: "single_select",
            required: true,
            sortOrder: 0,
            status: "active",
            options: [
              { optionId: "9f0b1c26-7d42-4f00-9a10-1234567890a2", label: "客厅", status: "active" },
              { optionId: "9f0b1c26-7d42-4f00-9a10-1234567890a3", label: "卧室", status: "active" }
            ]
          },
          {
            fieldId: "9f0b1c26-7d42-4f00-9a10-1234567890a4",
            label: "换水量",
            type: "number",
            required: false,
            sortOrder: 1,
            status: "active",
            unit: "升",
            min: "0.00",
            max: "99999.99"
          },
          {
            fieldId: "9f0b1c26-7d42-4f00-9a10-1234567890a5",
            label: "是否清洗滤材",
            type: "boolean",
            required: false,
            sortOrder: 2,
            status: "active"
          }
        ]
      }
    ]
  },
  {
    presetCategoryKey: "shopping",
    name: "采购",
    color: "#8D6E63",
    icon: { type: "iconKey", value: "shopping" },
    templates: [
      { presetKey: "shopping.grocery", name: "超市采购", measureMode: "event", weight: "1.00" }
    ]
  },
  {
    presetCategoryKey: "misc",
    name: "其他",
    color: "#8A8F99",
    icon: { type: "iconKey", value: "misc" },
    templates: [
      { presetKey: "misc.trash", name: "倒垃圾", measureMode: "event", weight: "0.50" }
    ]
  }
];

const PRESET_KEY_SET = new Set(PRESET_PACKS.flatMap((pack) => pack.templates.map((t) => t.presetKey)));
const PRESET_CATEGORY_BY_KEY = new Map(PRESET_PACKS.map((pack) => [pack.presetCategoryKey, pack]));

/**
 * 基础包（免导入播撒，spec: docs/CATEGORY_PRESETS_SPEC_20261002.md §5.0/§5.1，三模块统一收口）：
 * 全部 6 个预设分类 + 最常用模板核心（扫地/拖地/做饭/洗碗/洗衣/倒垃圾），
 * 「打开即可用」且不刷屏；其余模板（客厅吸尘/换洗床品/宠物喂食/清洗鱼缸/超市采购）
 * 仍走 presets/apply 显式导入。key 是否属于基础包以本结构为唯一权威。
 */
const HOUSEWORK_BASELINE = Object.freeze({
  presetCategoryKeys: Object.freeze(PRESET_PACKS.map((pack) => pack.presetCategoryKey)),
  templateKeys: Object.freeze([
    "cleaning.sweep",
    "cleaning.mop",
    "kitchen.cook",
    "kitchen.dishes",
    "laundry.wash",
    "misc.trash"
  ])
});

function presetPackByTemplateKey(key) {
  return PRESET_PACKS.find((pack) => pack.templates.some((t) => t.presetKey === key)) || null;
}

module.exports = { PRESET_PACKS, PRESET_KEY_SET, PRESET_CATEGORY_BY_KEY, HOUSEWORK_BASELINE, presetPackByTemplateKey };
