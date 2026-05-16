# 菜谱瀑布流推荐 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 RecipeFragment 内新增小红书风格双列瀑布流推荐视图，基于库存食材实时匹配排序。

**Architecture:** 服务端新增 `/api/recipes/recommend` 接口，查询 recipes + recipe_ingredients + inventory 三表实时计算匹配度排序。Android 端在 RecipeFragment 顶部加 Tab 切换，推荐视图用 StaggeredGridLayoutManager 双列瀑布流渲染。

**Tech Stack:** Node.js/Express (server), Java/AndroidX RecyclerView StaggeredGridLayoutManager + Glide (Android)

---

## File Structure

### Server — Create
- `server/src/routes/recipeRecommend.js` — 推荐路由（独立文件，保持 recipes.js 不膨胀）

### Server — Modify
- `server/src/cache.js:63-68` — 新增 `recipeRecommend` cache key 和 TTL
- `server/src/index.js:47-103` — 注册推荐路由

### Android — Create
- `app/src/main/res/layout/item_recipe_recommend.xml` — 瀑布流卡片布局
- `app/src/main/res/layout/layout_recipe_recommend.xml` — 推荐子视图（子Tab + Chip + RecyclerView）
- `app/src/main/java/com/example/couplecredit/adapter/RecommendAdapter.java` — 瀑布流 Adapter

### Android — Modify
- `app/src/main/java/com/example/couplecredit/api/AuthApiModels.java:744` — 新增推荐响应模型
- `app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:801` — 新增推荐 API 方法
- `app/src/main/res/layout/fragment_recipe.xml:8-12` — 顶部插入 Tab 栏，底部加推荐视图容器
- `app/src/main/java/com/example/couplecredit/fragment/RecipeFragment.java` — Tab 切换逻辑 + 推荐视图初始化

---

### Task 1: 服务端推荐 API

**Files:**
- Create: `server/src/routes/recipeRecommend.js`
- Modify: `server/src/cache.js:63-68`
- Modify: `server/src/index.js:47-103`

- [ ] **Step 1: 新增 cache key 和 TTL**

In `server/src/cache.js`, add to `Keys` object and `TTL` object:

```js
// In Keys:
recipeRecommend: (userId) => `recipe-rec:${userId}`,

// In TTL:
RECIPE_RECOMMEND: 30,
```

- [ ] **Step 2: 创建推荐路由**

Create `server/src/routes/recipeRecommend.js`:

```js
const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, parseRequiredInteger } = require("../utils/queryHelpers");

function createRecipeRecommendRouter({ pool }) {
  const router = express.Router();

  // GET /api/recipes/recommend?userId=&mode=&ingredient=
  router.get("/recommend", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));
      const mode = req.query.mode || "recommend";
      const ingredient = req.query.ingredient || null;

      const cacheKey = Keys.recipeRecommend(userId) + ":" + mode + ":" + (ingredient || "");
      const cached = cache.get(cacheKey);
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      // 1. Get all recipes for user + partner
      let recipeQuery, recipeParams;
      if (relationshipId) {
        recipeQuery = `SELECT r.recipe_id, r.title, r.description, r.image_url, r.category_id
                       FROM recipes r
                       WHERE r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL)`;
        recipeParams = [relationshipId, userId];
      } else {
        recipeQuery = `SELECT r.recipe_id, r.title, r.description, r.image_url, r.category_id
                       FROM recipes r WHERE r.user_id = ? AND r.relationship_id IS NULL`;
        recipeParams = [userId];
      }
      const [recipes] = await pool.execute(recipeQuery, recipeParams);

      if (recipes.length === 0) {
        const emptyResult = { ok: true, data: { recipes: [], availableIngredients: [] } };
        cache.set(cacheKey, emptyResult, TTL.RECIPE_RECOMMEND);
        return res.json(emptyResult);
      }

      // 2. Get all recipe ingredients
      const recipeIds = recipes.map(r => r.recipe_id);
      const riPlaceholders = recipeIds.map(() => "?").join(",");
      const [riRows] = await pool.execute(
        `SELECT recipe_id, ingredient_name FROM recipe_ingredients WHERE recipe_id IN (${riPlaceholders})`,
        recipeIds
      );
      const ingredientsByRecipe = {};
      for (const ri of riRows) {
        if (!ingredientsByRecipe[ri.recipe_id]) ingredientsByRecipe[ri.recipe_id] = [];
        ingredientsByRecipe[ri.recipe_id].push(ri.ingredient_name);
      }

      // 3. Get all inventory items
      let invQuery, invParams;
      if (relationshipId) {
        invQuery = `SELECT name FROM inventory WHERE (user_id = ? OR relationship_id = ?)`;
        invParams = [userId, relationshipId];
      } else {
        invQuery = `SELECT name FROM inventory WHERE user_id = ?`;
        invParams = [userId];
      }
      const [invRows] = await pool.execute(invQuery, invParams);
      const inventoryNames = new Set(invRows.map(r => r.name));

      // 4. Compute match info for each recipe
      const results = recipes.map(r => {
        const recipeIngredients = ingredientsByRecipe[r.recipe_id] || [];
        const matched = [];
        const missing = [];
        for (const name of recipeIngredients) {
          if (inventoryNames.has(name)) matched.push(name);
          else missing.push(name);
        }
        return {
          recipeId: r.recipe_id,
          title: r.title,
          description: r.description,
          imageUrl: r.image_url,
          categoryId: r.category_id,
          matchInfo: {
            total: recipeIngredients.length,
            matched: matched.length,
            matchedIngredients: matched,
            missingIngredients: missing
          }
        };
      });

      // 5. Sort by missing count ASC (best matches first)
      results.sort((a, b) => a.matchInfo.missingIngredients.length - b.matchInfo.missingIngredients.length);

      // 6. Filter by ingredient if mode=ingredient
      let filtered = results;
      if (mode === "ingredient" && ingredient) {
        filtered = results.filter(r =>
          r.matchInfo.matchedIngredients.includes(ingredient) ||
          r.matchInfo.missingIngredients.includes(ingredient)
        );
      }

      const availableIngredients = [...inventoryNames].sort();

      const responseData = {
        ok: true,
        data: { recipes: filtered, availableIngredients }
      };
      cache.set(cacheKey, responseData, TTL.RECIPE_RECOMMEND);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeRecommendRouter };
```

- [ ] **Step 3: 注册路由到 index.js**

In `server/src/index.js`, add after the `createRecipeRouter` import (around line 43):

```js
const { createRecipeRecommendRouter } = require("./routes/recipeRecommend");
```

Add route mount after `app.use("/api/recipes", createRecipeRouter({ pool }));` (around line 92):

```js
app.use("/api/recipes", createRecipeRecommendRouter({ pool }));
```

Note: This mounts the `/recommend` endpoint under `/api/recipes`, so the final path is `/api/recipes/recommend`.

- [ ] **Step 4: 重启服务端并测试**

Run:
```bash
lsof -ti:8082 | xargs kill -9; sleep 2; nohup node server/src/index.js > /tmp/couplecredit-server.log 2>&1 &
sleep 3; curl -s "http://localhost:8082/api/recipes/recommend?userId=1" | head -200
```

Expected: JSON response with `ok: true`, `recipes` array sorted by match quality, `availableIngredients` list.

- [ ] **Step 5: Commit**

```bash
git add server/src/routes/recipeRecommend.js server/src/cache.js server/src/index.js
git commit -m "feat(server): add recipe recommendation API with ingredient matching"
```

---

### Task 2: Android 端 API 模型和方法

**Files:**
- Modify: `app/src/main/java/com/example/couplecredit/api/AuthApiModels.java:744`
- Modify: `app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:801`

- [ ] **Step 1: 新增推荐响应模型**

In `AuthApiModels.java`, add after `RecipeCategoryListData` class (around line 744):

```java
// Recipe Recommendation
public static class RecipeRecommendResponse {
    public boolean ok;
    public RecipeRecommendData data;
    public ErrorBody error;
}

public static class RecipeRecommendData {
    public List<RecommendRecipeItem> recipes;
    public List<String> availableIngredients;
}

public static class RecommendRecipeItem {
    public int recipeId;
    public String title;
    public String description;
    public String imageUrl;
    public Integer categoryId;
    public MatchInfo matchInfo;
}

public static class MatchInfo {
    public int total;
    public int matched;
    public List<String> matchedIngredients;
    public List<String> missingIngredients;
}
```

- [ ] **Step 2: 新增推荐 API 回调接口和请求方法**

In `AuthApiClient.java`, add new callback interface (near other recipe callbacks around line 132):

```java
public interface RecipeRecommendCallback {
    void onSuccess(AuthApiModels.RecipeRecommendResponse response);
    void onError(String message);
}
```

Add new request method (near `queryRecipes` around line 709):

```java
public static void getRecipeRecommendations(Context context, int userId, String mode, String ingredient, RecipeRecommendCallback callback) {
    String url = "/api/recipes/recommend?userId=" + userId + "&mode=" + (mode != null ? mode : "recommend");
    if (ingredient != null && !ingredient.isEmpty()) {
        url += "&ingredient=" + java.net.URLEncoder.encode(ingredient, java.nio.charset.StandardCharsets.UTF_8);
    }
    doRequest(context, "GET", url, null, new RawCallback() {
        @Override public void onSuccess(String json) {
            if (callback == null) return;
            try {
                AuthApiModels.RecipeRecommendResponse r = GSON.fromJson(normalizeJsonPayload(json), AuthApiModels.RecipeRecommendResponse.class);
                if (r != null && r.ok) callback.onSuccess(r);
                else callback.onError(extractError(r != null ? r.error : null, json));
            } catch (Exception e) { callback.onError(buildParseError("菜谱推荐", json)); }
        }
        @Override public void onError(String m) { if (callback != null) callback.onError(m); }
    });
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/couplecredit/api/AuthApiModels.java app/src/main/java/com/example/couplecredit/api/AuthApiClient.java
git commit -m "feat(android): add recipe recommendation API models and client method"
```

---

### Task 3: 瀑布流卡片布局和 Adapter

**Files:**
- Create: `app/src/main/res/layout/item_recipe_recommend.xml`
- Create: `app/src/main/res/layout/layout_recipe_recommend.xml`
- Create: `app/src/main/java/com/example/couplecredit/adapter/RecommendAdapter.java`

- [ ] **Step 1: 创建瀑布流卡片布局**

Create `app/src/main/res/layout/item_recipe_recommend.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="4dp"
    app:cardCornerRadius="12dp"
    app:cardElevation="1dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <!-- 菜谱图片 -->
        <ImageView
            android:id="@+id/iv_recommend_image"
            android:layout_width="match_parent"
            android:layout_height="140dp"
            android:background="#F0F0F0"
            android:scaleType="centerCrop" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="10dp">

            <!-- 菜谱名称 -->
            <TextView
                android:id="@+id/tv_recommend_title"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:ellipsize="end"
                android:maxLines="2"
                android:text="菜谱名称"
                android:textColor="#1A1A1A"
                android:textSize="14sp"
                android:textStyle="bold" />

            <!-- 匹配标签 -->
            <TextView
                android:id="@+id/tv_recommend_match"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:background="@drawable/bg_match_full"
                android:paddingStart="6dp"
                android:paddingTop="2dp"
                android:paddingEnd="6dp"
                android:paddingBottom="2dp"
                android:text="食材齐全"
                android:textColor="#2E7D32"
                android:textSize="10sp" />

            <!-- 食材预览 -->
            <TextView
                android:id="@+id/tv_recommend_ingredients"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:ellipsize="end"
                android:maxLines="2"
                android:text="鸡蛋、番茄、盐"
                android:textColor="#999999"
                android:textSize="11sp" />
        </LinearLayout>
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

- [ ] **Step 2: 创建匹配标签背景 drawable**

Create `app/src/main/res/drawable/bg_match_full.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#E8F5E9" />
    <corners android:radius="8dp" />
</shape>
```

Create `app/src/main/res/drawable/bg_match_partial.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FFF3E0" />
    <corners android:radius="8dp" />
</shape>
```

Create `app/src/main/res/drawable/bg_match_missing.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FFEBEE" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 3: 创建推荐子视图布局**

Create `app/src/main/res/layout/layout_recipe_recommend.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F5F5F5"
    android:orientation="vertical">

    <!-- 子 Tab 栏 -->
    <LinearLayout
        android:id="@+id/layout_recommend_tabs"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@android:color/white"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="16dp"
        android:paddingEnd="16dp">

        <TextView
            android:id="@+id/tab_recommend_all"
            android:layout_width="wrap_content"
            android:layout_height="28dp"
            android:background="@drawable/bg_recommend_tab_active"
            android:gravity="center"
            android:paddingStart="14dp"
            android:paddingEnd="14dp"
            android:text="推荐"
            android:textColor="#FFFFFF"
            android:textSize="13sp" />

        <TextView
            android:id="@+id/tab_recommend_ingredient"
            android:layout_width="wrap_content"
            android:layout_height="28dp"
            android:layout_marginStart="8dp"
            android:background="@drawable/bg_recommend_tab_inactive"
            android:gravity="center"
            android:paddingStart="14dp"
            android:paddingEnd="14dp"
            android:text="按食材"
            android:textColor="#666666"
            android:textSize="13sp" />
    </LinearLayout>

    <!-- 食材 Chip 横向滚动（按食材模式显示） -->
    <HorizontalScrollView
        android:id="@+id/scroll_ingredient_chips"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@android:color/white"
        android:paddingStart="12dp"
        android:paddingTop="4dp"
        android:paddingEnd="12dp"
        android:paddingBottom="8dp"
        android:scrollbars="none"
        android:visibility="gone">

        <com.google.android.material.chip.ChipGroup
            android:id="@+id/chip_group_ingredients"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:singleSelection="true"
            xmlns:app="http://schemas.android.com/apk/res-auto" />
    </HorizontalScrollView>

    <!-- 瀑布流 RecyclerView -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_recommend_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:clipToPadding="false"
        android:paddingStart="4dp"
        android:paddingTop="8dp"
        android:paddingEnd="4dp"
        android:paddingBottom="136dp" />

    <!-- 空状态 -->
    <LinearLayout
        android:id="@+id/ll_recommend_empty"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:gravity="center"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="还没有菜谱推荐"
            android:textColor="#999999"
            android:textSize="15sp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="添加菜谱和库存食材后即可获得推荐"
            android:textColor="#BBBBBB"
            android:textSize="12sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 4: 创建 Tab 背景 drawable**

Create `app/src/main/res/drawable/bg_recommend_tab_active.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#07C160" />
    <corners android:radius="14dp" />
</shape>
```

Create `app/src/main/res/drawable/bg_recommend_tab_inactive.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#F0F0F0" />
    <corners android:radius="14dp" />
</shape>
```

- [ ] **Step 5: 创建 RecommendAdapter**

Create `app/src/main/java/com/example/couplecredit/adapter/RecommendAdapter.java`:

```java
package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.ViewHolder> {

    private final List<AuthApiModels.RecommendRecipeItem> items = new ArrayList<>();
    private final OnRecipeClickListener listener;
    private static final Random random = new Random();

    public interface OnRecipeClickListener {
        void onRecipeClick(int recipeId);
    }

    public RecommendAdapter(OnRecipeClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<AuthApiModels.RecommendRecipeItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recipe_recommend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.RecommendRecipeItem item = items.get(position);

        holder.tvTitle.setText(item.title);

        // Image loading
        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            Glide.with(holder.ivImage.getContext())
                    .load(item.imageUrl)
                    .centerCrop()
                    .into(holder.ivImage);
        } else {
            int height = 120 + random.nextInt(80);
            ViewGroup.LayoutParams lp = holder.ivImage.getLayoutParams();
            lp.height = (int) (height * holder.ivImage.getContext().getResources().getDisplayMetrics().density);
            holder.ivImage.setLayoutParams(lp);
            holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
        }

        // Match info
        AuthApiModels.MatchInfo match = item.matchInfo;
        if (match != null) {
            int missing = match.missingIngredients != null ? match.missingIngredients.size() : 0;
            if (missing == 0) {
                holder.tvMatch.setText("食材齐全");
                holder.tvMatch.setTextColor(0xFF2E7D32);
                holder.tvMatch.setBackgroundResource(R.drawable.bg_match_full);
            } else {
                holder.tvMatch.setText("缺" + missing + "样");
                if (missing <= 1) {
                    holder.tvMatch.setTextColor(0xFFE65100);
                    holder.tvMatch.setBackgroundResource(R.drawable.bg_match_partial);
                } else {
                    holder.tvMatch.setTextColor(0xFFC62828);
                    holder.tvMatch.setBackgroundResource(R.drawable.bg_match_missing);
                }
            }

            // Ingredient preview
            StringBuilder preview = new StringBuilder();
            if (match.matchedIngredients != null) {
                for (int i = 0; i < Math.min(match.matchedIngredients.size(), 3); i++) {
                    if (preview.length() > 0) preview.append("、");
                    preview.append(match.matchedIngredients.get(i));
                }
            }
            if (match.missingIngredients != null && !match.missingIngredients.isEmpty()) {
                for (int i = 0; i < match.missingIngredients.size() && preview.length() < 20; i++) {
                    preview.append("、❌").append(match.missingIngredients.get(i));
                }
            }
            holder.tvIngredients.setText(preview.toString());
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onRecipeClick(item.recipeId);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvTitle;
        TextView tvMatch;
        TextView tvIngredients;

        ViewHolder(View view) {
            super(view);
            ivImage = view.findViewById(R.id.iv_recommend_image);
            tvTitle = view.findViewById(R.id.tv_recommend_title);
            tvMatch = view.findViewById(R.id.tv_recommend_match);
            tvIngredients = view.findViewById(R.id.tv_recommend_ingredients);
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/item_recipe_recommend.xml app/src/main/res/layout/layout_recipe_recommend.xml app/src/main/res/drawable/bg_match_full.xml app/src/main/res/drawable/bg_match_partial.xml app/src/main/res/drawable/bg_match_missing.xml app/src/main/res/drawable/bg_recommend_tab_active.xml app/src/main/res/drawable/bg_recommend_tab_inactive.xml app/src/main/java/com/example/couplecredit/adapter/RecommendAdapter.java
git commit -m "feat(android): add recommendation waterfall layout and adapter"
```

---

### Task 4: RecipeFragment 集成推荐视图

**Files:**
- Modify: `app/src/main/res/layout/fragment_recipe.xml`
- Modify: `app/src/main/java/com/example/couplecredit/fragment/RecipeFragment.java`

- [ ] **Step 1: 修改 fragment_recipe.xml 添加 Tab 栏和推荐容器**

Insert a Tab bar between the header LinearLayout (ends around line 96) and the `layout_login_prompt`. Also add the recommend view container alongside `layout_content`.

The key changes to `fragment_recipe.xml`:

1. After the header `</LinearLayout>` (line 96), add a Tab bar row:

```xml
<!-- 主 Tab 栏: 我的菜谱 / 推荐 -->
<LinearLayout
    android:id="@+id/layout_main_tabs"
    android:layout_width="match_parent"
    android:layout_height="40dp"
    android:background="@android:color/white"
    android:gravity="center"
    android:orientation="horizontal"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:visibility="gone">

    <TextView
        android:id="@+id/tab_my_recipes"
        android:layout_width="0dp"
        android:layout_height="32dp"
        android:layout_weight="1"
        android:gravity="center"
        android:text="我的菜谱"
        android:textColor="#07C160"
        android:textSize="15sp"
        android:textStyle="bold" />

    <View
        android:layout_width="1dp"
        android:layout_height="20dp"
        android:background="#EEEEEE" />

    <TextView
        android:id="@+id/tab_recommend"
        android:layout_width="0dp"
        android:layout_height="32dp"
        android:layout_weight="1"
        android:gravity="center"
        android:text="推荐"
        android:textColor="#999999"
        android:textSize="15sp" />
</LinearLayout>
```

2. Wrap `layout_content` and a new recommend container in a FrameLayout so they can be switched. Replace the existing `layout_content` LinearLayout (line 128-211) — keep it as-is but wrap it and add a sibling `<include>` for the recommend view:

```xml
<FrameLayout
    android:id="@+id/layout_views_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- 原有的 我的菜谱 视图 -->
    <LinearLayout
        android:id="@+id/layout_content"
        ...existing content unchanged... />

    <!-- 推荐视图 -->
    <include
        android:id="@+id/layout_recommend_view"
        layout="@layout/layout_recipe_recommend"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />
</FrameLayout>
```

- [ ] **Step 2: 修改 RecipeFragment.java 添加 Tab 切换和推荐加载逻辑**

Add these fields to RecipeFragment class (after existing fields around line 79):

```java
// 推荐视图相关
private View layoutMainTabs;
private TextView tabMyRecipes;
private TextView tabRecommend;
private View layoutRecommendView;
private RecyclerView rvRecommendList;
private RecommendAdapter recommendAdapter;
private LinearLayout llRecommendEmpty;
private TextView tabRecommendAll;
private TextView tabRecommendIngredient;
private HorizontalScrollView scrollIngredientChips;
private com.google.android.material.chip.ChipGroup chipGroupIngredients;
private List<String> availableIngredients = new ArrayList<>();
private String selectedIngredient = null;
private boolean isRecommendMode = false;
```

In `onViewCreated()`, after existing view bindings (around line 106), add:

```java
// 主 Tab
layoutMainTabs = view.findViewById(R.id.layout_main_tabs);
tabMyRecipes = view.findViewById(R.id.tab_my_recipes);
tabRecommend = view.findViewById(R.id.tab_recommend);
layoutRecommendView = view.findViewById(R.id.layout_recommend_view);

// 推荐子视图
rvRecommendList = layoutRecommendView.findViewById(R.id.rv_recommend_list);
llRecommendEmpty = layoutRecommendView.findViewById(R.id.ll_recommend_empty);
tabRecommendAll = layoutRecommendView.findViewById(R.id.tab_recommend_all);
tabRecommendIngredient = layoutRecommendView.findViewById(R.id.tab_recommend_ingredient);
scrollIngredientChips = layoutRecommendView.findViewById(R.id.scroll_ingredient_chips);
chipGroupIngredients = layoutRecommendView.findViewById(R.id.chip_group_ingredients);

rvRecommendList.setLayoutManager(new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
recommendAdapter = new RecommendAdapter(recipeId -> showRecipeDetail(recipeId));
rvRecommendList.setAdapter(recommendAdapter);

// 主 Tab 点击
tabMyRecipes.setOnClickListener(v -> switchToMyRecipes());
tabRecommend.setOnClickListener(v -> switchToRecommend());

// 子 Tab 点击
tabRecommendAll.setOnClickListener(v -> {
    tabRecommendAll.setBackgroundResource(R.drawable.bg_recommend_tab_active);
    tabRecommendAll.setTextColor(0xFFFFFFFF);
    tabRecommendIngredient.setBackgroundResource(R.drawable.bg_recommend_tab_inactive);
    tabRecommendIngredient.setTextColor(0xFF666666);
    scrollIngredientChips.setVisibility(View.GONE);
    selectedIngredient = null;
    loadRecommendations();
});
tabRecommendIngredient.setOnClickListener(v -> {
    tabRecommendIngredient.setBackgroundResource(R.drawable.bg_recommend_tab_active);
    tabRecommendIngredient.setTextColor(0xFFFFFFFF);
    tabRecommendAll.setBackgroundResource(R.drawable.bg_recommend_tab_inactive);
    tabRecommendAll.setTextColor(0xFF666666);
    scrollIngredientChips.setVisibility(View.VISIBLE);
});
```

Add these methods to RecipeFragment:

```java
private void switchToMyRecipes() {
    isRecommendMode = false;
    tabMyRecipes.setTextColor(0xFF07C160);
    tabMyRecipes.setTypeface(null, android.graphics.Typeface.BOLD);
    tabRecommend.setTextColor(0xFF999999);
    tabRecommend.setTypeface(null, android.graphics.Typeface.NORMAL);
    layoutContent.setVisibility(View.VISIBLE);
    layoutRecommendView.setVisibility(View.GONE);
}

private void switchToRecommend() {
    isRecommendMode = true;
    tabRecommend.setTextColor(0xFF07C160);
    tabRecommend.setTypeface(null, android.graphics.Typeface.BOLD);
    tabMyRecipes.setTextColor(0xFF999999);
    tabMyRecipes.setTypeface(null, android.graphics.Typeface.NORMAL);
    layoutContent.setVisibility(View.GONE);
    layoutRecommendView.setVisibility(View.VISIBLE);
    loadRecommendations();
}

private void loadRecommendations() {
    if (!isLoggedIn) return;
    int userId = UserInfoManager.getCurrentUserId(requireContext());
    String mode = selectedIngredient != null ? "ingredient" : "recommend";
    AuthApiClient.getRecipeRecommendations(requireContext(), userId, mode, selectedIngredient, new AuthApiClient.RecipeRecommendCallback() {
        @Override
        public void onSuccess(AuthApiModels.RecipeRecommendResponse response) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                recommendAdapter.setData(response.data.recipes);
                llRecommendEmpty.setVisibility(response.data.recipes.isEmpty() ? View.VISIBLE : View.GONE);
                rvRecommendList.setVisibility(response.data.recipes.isEmpty() ? View.GONE : View.VISIBLE);
                if (response.data.availableIngredients != null) {
                    availableIngredients.clear();
                    availableIngredients.addAll(response.data.availableIngredients);
                    updateIngredientChips();
                }
            });
        }

        @Override
        public void onError(String message) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), "加载推荐失败: " + message, Toast.LENGTH_SHORT).show()
            );
        }
    });
}

private void updateIngredientChips() {
    chipGroupIngredients.removeAllViews();
    for (String ingredient : availableIngredients) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(requireContext());
        chip.setText(ingredient);
        chip.setClickable(true);
        chip.setCheckable(true);
        chip.setOnClickListener(v -> {
            if (chip.isChecked()) {
                selectedIngredient = ingredient;
                loadRecommendations();
            } else {
                selectedIngredient = null;
                loadRecommendations();
            }
        });
        chipGroupIngredients.addView(chip);
    }
}
```

Update the existing `refreshData()` method (or equivalent) to also refresh recommendations when in recommend mode. Find the method that loads recipes and add at the end:

```java
if (isRecommendMode) {
    loadRecommendations();
}
```

Show the main Tab bar only when logged in. In the existing login state handling section (where `layoutLoginPrompt` visibility is managed), add:

```java
layoutMainTabs.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/fragment_recipe.xml app/src/main/java/com/example/couplecredit/fragment/RecipeFragment.java
git commit -m "feat(android): integrate recommendation waterfall view into RecipeFragment"
```

---

### Task 5: 端到端验证

**Files:** None new

- [ ] **Step 1: 重启服务端**

```bash
lsof -ti:8082 | xargs kill -9; sleep 2; nohup node server/src/index.js > /tmp/couplecredit-server.log 2>&1 &
sleep 3; curl -s "http://localhost:8082/api/health" | head -5
```

Expected: `{"ok":true,...}`

- [ ] **Step 2: 测试推荐 API**

```bash
curl -s "http://localhost:8082/api/recipes/recommend?userId=1" | python3 -m json.tool | head -40
```

Expected: Response with `ok: true`, recipes sorted by match quality.

- [ ] **Step 3: Android 编译**

Build and run the Android app. Navigate to 菜谱 tab, verify:
1. Tab bar shows "我的菜谱" (active, green bold) and "推荐" (grey)
2. Existing recipe list displays normally
3. Tap "推荐" → switches to waterfall view
4. Tap "按食材" → shows ingredient chips, tap a chip to filter
5. Tap a recipe card → opens recipe detail dialog
6. Switch back to "我的菜谱" → shows original view

- [ ] **Step 4: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: recipe recommendation integration fixes"
```
