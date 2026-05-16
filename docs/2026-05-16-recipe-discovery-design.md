# 菜谱瀑布流推荐功能设计

## 概述

在 RecipeFragment 内新增一个「推荐」视图，以小红书风格双列瀑布流展示菜谱。基于用户自有菜谱和库存食材实时匹配排序，食材齐全的菜谱排前面。

## 已确定的设计决策

| 决策 | 选择 |
|------|------|
| UI 风格 | 小红书双列瀑布流 (StaggeredGridLayoutManager) |
| 数据源 | 自有菜谱 + 库存食材匹配 |
| 推荐逻辑 | 实时计算，每次打开重新匹配 |
| 入口位置 | RecipeFragment 顶部 Tab 栏切换 |
| 收藏功能 | 第一版不做，后续迭代 |

## UI 结构

### Tab 栏

RecipeFragment 顶部新增 Tab 栏，两个选项：

- **「我的菜谱」** — 现有的分类列表 + 菜谱列表视图（保持不变）
- **「推荐」** — 新的瀑布流推荐视图

默认停留在「我的菜谱」，Tab 切换时隐藏/显示对应视图，不销毁。

### 推荐视图布局

```
┌──────────────────────────┐
│ [推荐]  [按食材]          │  ← 筛选 Tab（推荐视图内部的子 Tab）
├──────────────────────────┤
│  ┌──────┐  ┌──────────┐  │
│  │ 🍳   │  │ 🍲       │  │
│  │番茄炒蛋│  │ 紫菜蛋花汤│  │
│  │食材齐全│  │ 食材齐全  │  │
│  └──────┘  ├──────────┤  │
│  ┌──────┐  │ 🥘       │  │
│  │ 🥗   │  │ 红烧肉    │  │
│  │凉拌黄瓜│  │ 缺五花肉  │  │
│  │食材齐全│  └──────────┘  │
│  └──────┘  ┌──────────┐  │
│            │ 🫕       │  │
│            │ 酸菜鱼    │  │
│            │ 缺2样     │  │
│            └──────────┘  │
├──────────────────────────┤
│     ↓ 下拉加载更多        │
└──────────────────────────┘
```

### 子 Tab 说明

**「推荐」**：按食材匹配度排序所有菜谱（食材齐全 → 缺1样 → 缺2样... → 缺最多）

**「按食材」**：顶部显示库存食材标签（横向滚动 Chip 组），点选食材后筛选出包含该食材的菜谱，匹配度排序

### 卡片内容

每张瀑布流卡片包含：
- 菜谱图片（有图显示图片，无图显示渐变色占位 + emoji）
- 菜谱名称
- 食材匹配标签（绿底"食材齐全" / 橙底"缺N样"）
- 已有/缺少的食材预览（最多显示 3 个）
- 缺少的食材标红提示

点击卡片 → 弹出现有的菜谱详情 Dialog

## 服务端 API

### GET /api/recipes/recommend?userId=&mode=

新增推荐接口，复用现有 recipes 查询逻辑。

**参数：**
- `userId` — 当前用户 ID
- `mode` — `recommend`（默认）或 `ingredient`
- `ingredient` — 当 mode=ingredient 时，指定筛选的食材名称（可选）

**返回逻辑：**

1. 查询用户 + 伴侣的所有菜谱（复用现有 `GET /api/recipes` 的数据范围）
2. 查询用户 + 伴侣的所有库存食材（`inventory` 表，按 `item_name` 提取）
3. 查询每道菜的 `recipe_ingredients`
4. 对每道菜计算：已有食材数 / 总食材数，得出匹配率
5. 按 `缺失数 ASC` 排序（食材齐全的排前面）
6. 如果 mode=ingredient，只返回包含该食材的菜谱

**返回格式（扩展现有 RecipeItemData）：**

```json
{
  "recipes": [
    {
      "recipeId": 1,
      "title": "番茄炒蛋",
      "imageUrl": "...",
      "description": "...",
      "categoryId": 2,
      "matchInfo": {
        "total": 3,
        "matched": 3,
        "missingIngredients": [],
        "matchedIngredients": ["鸡蛋", "番茄", "盐"]
      }
    }
  ],
  "availableIngredients": ["番茄", "鸡蛋", "黄瓜", "猪肉", "盐"]
}
```

`availableIngredients` 是用户库存中所有食材名称列表，用于「按食材」Tab 的标签展示。

### 数据库变更

无需新建表，完全基于现有 `recipes` + `recipe_ingredients` + `inventory` 三表实时 JOIN 计算。

## Android 端实现

### 新增文件

| 文件 | 说明 |
|------|------|
| `RecommendAdapter.java` | 瀑布流卡片 RecyclerView.Adapter，使用 StaggeredGridLayoutManager |
| `item_recipe_recommend.xml` | 瀑布流卡片布局（图片 + 标题 + 匹配信息） |
| `layout_recipe_recommend.xml` | 推荐视图根布局（子 Tab + 筛选 Chip + 瀑布流 RecyclerView） |

### 修改文件

| 文件 | 改动 |
|------|------|
| `fragment_recipe.xml` | 顶部加 Tab 栏（我的菜谱 / 推荐），推荐视图容器 |
| `RecipeFragment.java` | Tab 切换逻辑，推荐视图初始化，子 Tab 切换，食材 Chip 筛选 |
| `AuthApiClient.java` | 新增 `getRecipeRecommendations()` 方法 |
| `AuthApiModels.java` | 新增推荐相关的请求/响应模型 |

### RecommendAdapter

- 继承 `RecyclerView.Adapter<RecommendViewHolder>`
- 使用 `StaggeredGridLayoutManager(2, VERTICAL)`
- 卡片图片高度根据图片比例动态设置（有图时 Glide 加载，无图时随机 120-200dp 高度渐变色占位）
- 点击事件回调到 RecipeFragment，复用现有的 `showRecipeDetail()` 弹窗

### 数据流

```
用户切换到「推荐」Tab
  → RecipeFragment 调用 AuthApiClient.getRecipeRecommendations(userId, mode, ingredient)
  → 服务端查询 recipes + ingredients + inventory，计算匹配率，排序返回
  → 客户端用 RecommendAdapter 渲染瀑布流
  → 用户切换子 Tab「按食材」时，使用已缓存的 availableIngredients 渲染 Chip
  → 点选食材 Chip 后，重新请求 mode=ingredient&ingredient=xxx
```

## 性能考虑

- 服务端推荐接口加 30 秒 TTL 缓存（与现有菜谱缓存一致）
- 按食材筛选可前端缓存复用，切换 Chip 时前端过滤而非重新请求
- 瀑布流图片用 Glide 缩略图加载，避免大图卡顿
- 分页加载：初始加载 20 条，滚动到底部加载更多（后续优化，第一版可全量加载）

## 不在本版范围内

- 收藏菜谱功能
- 每日推荐定时推送
- 菜谱评分 / 浏览量排序
- 外部菜谱 API 接入
- AI 菜谱推荐
