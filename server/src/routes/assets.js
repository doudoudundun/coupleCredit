const express = require("express");
const sharp = require("sharp");
const path = require("path");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger, normalizeNullableText, invalidateForUser } = require("../utils/queryHelpers");

const ASSET_SELECT_FIELDS = `asset_id as assetId, user_id as userId, relationship_id as relationshipId,
    name, category, image_url as imageUrl, original_image_url as originalImageUrl,
    DATE_FORMAT(purchase_date, '%Y-%m-%d') as purchaseDate,
    purchase_price as purchasePrice, current_value as currentValue,
    status, note, disposed_at as disposedAt,
    created_at as createdAt, updated_at as updatedAt`;

const DEFAULT_CATEGORIES = [
  '电子设备', '衣物', '包包', '鞋履', '家具', '配饰', '美妆', '书籍', '其他'
];

function ownershipWhere(relationshipId, userId) {
  if (relationshipId) {
    return { clause: "(relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))", params: [relationshipId, userId] };
  }
  return { clause: "user_id = ? AND relationship_id IS NULL", params: [userId] };
}

function computeMetrics(row, now) {
  const purchaseDate = row.purchaseDate ? new Date(row.purchaseDate) : null;
  let holdDays = 0;
  if (purchaseDate) {
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const purchased = new Date(purchaseDate.getFullYear(), purchaseDate.getMonth(), purchaseDate.getDate());
    holdDays = Math.max(1, Math.round((today - purchased) / (1000 * 60 * 60 * 24)));
  }
  const dailyCost = row.purchasePrice ? row.purchasePrice / holdDays : 0;
  return {
    ...row,
    holdDays,
    dailyCost: Math.round(dailyCost * 100) / 100
  };
}

function createAssetsRouter({ pool }) {
  const router = express.Router();

  function invalidateAssetsCache(userId, relationship) {
    invalidateForUser(cache, Keys.assets, userId, relationship);
    invalidateForUser(cache, Keys.assetStats, userId, relationship);
    invalidateForUser(cache, Keys.assetCategories, userId, relationship);
  }

  // GET /api/assets - List assets
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const category = req.query.category ? trimValue(req.query.category) : null;
      const status = req.query.status ? trimValue(req.query.status) : null;

      const cached = cache.get(Keys.assets(userId));
      if (cached && !category && !status) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const scope = ownershipWhere(relationshipId, userId);
      let query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE ${scope.clause}`;
      let params = [...scope.params];

      if (category) {
        query += " AND category = ?";
        params.push(category);
      }
      if (status) {
        query += " AND status = ?";
        params.push(status);
      }

      query += " ORDER BY created_at DESC";

      const [rows] = await pool.execute(query, params);

      const now = new Date();
      const items = rows.map(row => computeMetrics(row, now));

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { items, relationshipId }
      };
      cache.set(Keys.assets(userId), responseData, TTL.ASSETS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/stats - Asset statistics
  router.get("/stats", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const cached = cache.get(Keys.assetStats(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const scope = ownershipWhere(relationshipId, userId);
      const scopeClause = `WHERE ${scope.clause}`;

      const [[{ totalCount, totalValue: sqlTotalValue }]] = await pool.execute(
        `SELECT COUNT(*) as totalCount,
                COALESCE(SUM(CASE WHEN status IN ('active','idle') THEN purchase_price END), 0) as totalValue
         FROM assets ${scopeClause}`,
        scope.params
      );

      const [categoryRows] = await pool.execute(
        `SELECT category, COUNT(*) as count, COALESCE(SUM(purchase_price), 0) as totalValue
         FROM assets ${scopeClause} GROUP BY category`, scope.params
      );
      const categoryBreakdown = categoryRows.map(r => ({
        category: r.category,
        count: r.count,
        totalValue: Math.round(Number(r.totalValue) * 100) / 100
      }));

      const [statusRows] = await pool.execute(
        `SELECT status, COUNT(*) as count FROM assets ${scopeClause} GROUP BY status`, scope.params
      );
      const statusBreakdown = { active: 0, idle: 0, disposed: 0 };
      statusRows.forEach(r => { statusBreakdown[r.status] = r.count; });

      const [latestRows] = await pool.execute(
        `SELECT ${ASSET_SELECT_FIELDS} FROM assets ${scopeClause}
         ORDER BY created_at DESC LIMIT 1`, scope.params
      );
      const latestItem = latestRows.length > 0 ? latestRows[0] : null;

      const [allRows] = await pool.execute(
        `SELECT purchase_price as purchasePrice, purchase_date as purchaseDate
         FROM assets ${scopeClause}`, scope.params
      );
      const now = new Date();
      let totalDailyCost = 0;
      allRows.forEach(row => {
        totalDailyCost += computeMetrics(row, now).dailyCost;
      });

      const responseData = {
        ok: true,
        message: "查询成功",
        data: {
          totalValue: Math.round(Number(sqlTotalValue) * 100) / 100,
          totalCount,
          dailyAvgCost: Math.round(totalDailyCost * 100) / 100,
          categoryBreakdown,
          statusBreakdown,
          latestItem
        }
      };

      cache.set(Keys.assetStats(userId), responseData, TTL.ASSETS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/categories - List categories
  router.get("/categories", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const cached = cache.get(Keys.assetCategories(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const scope = ownershipWhere(relationshipId, userId);
      const query = `SELECT name, is_default as isDefault FROM asset_categories
               WHERE is_default = TRUE OR ${scope.clause}
               ORDER BY is_default DESC, created_at ASC`;

      const [rows] = await pool.execute(query, scope.params);

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { categories: rows.map(r => r.name) }
      };

      cache.set(Keys.assetCategories(userId), responseData, TTL.ASSET_CATS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // POST /api/assets/categories - Add custom category
  router.post("/categories", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);

      if (!name) {
        throw new ApiError(400, "INVALID_REQUEST", "分类名称不能为空");
      }

      if (DEFAULT_CATEGORIES.includes(name)) {
        throw new ApiError(400, "INVALID_REQUEST", "该分类已存在");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      await pool.execute(
        "INSERT INTO asset_categories (user_id, relationship_id, name, is_default) VALUES (?, ?, ?, FALSE)",
        [userId, relationshipId, name]
      );

      invalidateAssetsCache(userId, relationship);
      res.status(201).json({
        ok: true,
        message: "分类添加成功",
        data: { name }
      });
    } catch (error) {
      if (error.code === 'ER_DUP_ENTRY') {
        return res.status(400).json({
          ok: false,
          error: { code: "DUPLICATE", message: "该分类已存在" }
        });
      }
      next(error);
    }
  });

  // POST /api/assets - Create asset
  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      const category = trimValue(req.body.category);

      if (!name || !category) {
        throw new ApiError(400, "INVALID_REQUEST", "资产名称和分类不能为空");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const imageUrl = normalizeNullableText(req.body.imageUrl);
      const originalImageUrl = normalizeNullableText(req.body.originalImageUrl);
      const purchaseDate = normalizeNullableText(req.body.purchaseDate);
      const purchasePrice = req.body.purchasePrice != null ? Number(req.body.purchasePrice) : null;
      const currentValue = req.body.currentValue != null ? Number(req.body.currentValue) : null;
      const status = req.body.status || 'active';
      const note = normalizeNullableText(req.body.note);

      const [result] = await pool.execute(
        `INSERT INTO assets (user_id, relationship_id, name, category, image_url, original_image_url,
         purchase_date, purchase_price, current_value, status, note)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [userId, relationshipId, name, category, imageUrl, originalImageUrl,
         purchaseDate, purchasePrice, currentValue, status, note]
      );

      invalidateAssetsCache(userId, relationship);
      res.status(201).json({
        ok: true,
        message: "资产添加成功",
        data: { assetId: result.insertId }
      });
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/:id - Get single asset
  router.get("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const scope = ownershipWhere(relationshipId, userId);
      const [rows] = await pool.execute(
        `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE asset_id = ? AND ${scope.clause}`,
        [assetId, ...scope.params]
      );
      if (rows.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在");
      }

      res.json({
        ok: true,
        message: "查询成功",
        data: computeMetrics(rows[0], new Date())
      });
    } catch (error) {
      next(error);
    }
  });

  // PUT /api/assets/:id - Update asset
  router.put("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);

      const updates = [];
      const params = [];

      if (req.body.name !== undefined) {
        const name = trimValue(req.body.name);
        if (!name) throw new ApiError(400, "INVALID_REQUEST", "资产名称不能为空");
        updates.push("name = ?");
        params.push(name);
      }

      if (req.body.category !== undefined) {
        const category = trimValue(req.body.category);
        if (!category) throw new ApiError(400, "INVALID_REQUEST", "分类不能为空");
        updates.push("category = ?");
        params.push(category);
      }

      if (req.body.imageUrl !== undefined) {
        updates.push("image_url = ?");
        params.push(normalizeNullableText(req.body.imageUrl));
      }

      if (req.body.purchaseDate !== undefined) {
        updates.push("purchase_date = ?");
        params.push(normalizeNullableText(req.body.purchaseDate));
      }

      if (req.body.purchasePrice !== undefined) {
        updates.push("purchase_price = ?");
        params.push(req.body.purchasePrice != null ? Number(req.body.purchasePrice) : null);
      }

      if (req.body.currentValue !== undefined) {
        updates.push("current_value = ?");
        params.push(req.body.currentValue != null ? Number(req.body.currentValue) : null);
      }

      if (req.body.note !== undefined) {
        updates.push("note = ?");
        params.push(normalizeNullableText(req.body.note));
      }

      if (updates.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有提供要更新的字段");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const scope = ownershipWhere(relationshipId, userId);

      updates.push("updated_at = NOW()");
      params.push(assetId, ...scope.params);

      const [result] = await pool.execute(
        `UPDATE assets SET ${updates.join(", ")} WHERE asset_id = ? AND ${scope.clause}`,
        params
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权修改");
      }

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "资产更新成功" });
    } catch (error) {
      next(error);
    }
  });

  // PATCH /api/assets/:id/status - Update status
  router.patch("/:id/status", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const status = trimValue(req.body.status);

      if (!['active', 'idle', 'disposed'].includes(status)) {
        throw new ApiError(400, "INVALID_REQUEST", "状态值不正确");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const scope = ownershipWhere(relationshipId, userId);

      const disposedAt = status === 'disposed' ? "NOW()" : "NULL";
      const [result] = await pool.execute(
        `UPDATE assets SET status = ?, disposed_at = ${disposedAt}, updated_at = NOW() WHERE asset_id = ? AND ${scope.clause}`,
        [status, assetId, ...scope.params]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权修改");
      }

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "状态更新成功" });
    } catch (error) {
      next(error);
    }
  });

  // DELETE /api/assets/:id - Delete asset
  router.delete("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const scope = ownershipWhere(relationshipId, userId);

      const [result] = await pool.execute(
        `DELETE FROM assets WHERE asset_id = ? AND ${scope.clause}`,
        [assetId, ...scope.params]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权删除");
      }

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "资产删除成功" });
    } catch (error) {
      next(error);
    }
  });

  // POST /api/assets/remove-bg - Remove background from image
  router.post("/remove-bg", async (req, res, next) => {
    try {
      const { imageUrl } = req.body;
      if (!imageUrl) {
        throw new ApiError(400, "INVALID_REQUEST", "请提供图片URL");
      }

      const imagePath = path.resolve(__dirname, "../../uploads", path.basename(imageUrl));
      const outputFilename = `nobg_${Date.now()}.png`;
      const outputPath = path.resolve(__dirname, "../../uploads", outputFilename);

      const { data, info } = await sharp(imagePath)
        .resize(600, 600, { fit: "inside", withoutEnlargement: true })
        .ensureAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });

      const w = info.width;
      const h = info.height;
      const ch = info.channels;
      const total = w * h;

      function idx(x, y) { return y * w + x; }
      function rgb(px) { return [data[px], data[px + 1], data[px + 2]]; }
      function dist3(a, b) {
        const dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
      }

      // --- Step 1: Collect border pixels, cluster to find dominant background colors ---
      const borderColors = [];
      const seen = new Set();
      for (let x = 0; x < w; x++) {
        for (const row of [0, h - 1]) {
          const c = rgb(idx(x, row) * ch);
          const key = (c[0] >> 4) << 8 | (c[1] >> 4) << 4 | (c[2] >> 4);
          if (!seen.has(key)) { seen.add(key); borderColors.push(c); }
        }
      }
      for (let y = 1; y < h - 1; y++) {
        for (const col of [0, w - 1]) {
          const c = rgb(idx(col, y) * ch);
          const key = (c[0] >> 4) << 8 | (c[1] >> 4) << 4 | (c[2] >> 4);
          if (!seen.has(key)) { seen.add(key); borderColors.push(c); }
        }
      }

      // Simple k-means with k=3 to find dominant background cluster
      const CLUSTER_COUNT = 3;
      let centroids = borderColors.slice(0, CLUSTER_COUNT);
      for (let iter = 0; iter < 10; iter++) {
        const sums = Array.from({ length: CLUSTER_COUNT }, () => [0, 0, 0]);
        const counts = new Array(CLUSTER_COUNT).fill(0);
        for (const c of borderColors) {
          let minD = Infinity, minI = 0;
          for (let k = 0; k < CLUSTER_COUNT; k++) {
            const d = dist3(c, centroids[k]);
            if (d < minD) { minD = d; minI = k; }
          }
          sums[minI][0] += c[0]; sums[minI][1] += c[1]; sums[minI][2] += c[2];
          counts[minI]++;
        }
        for (let k = 0; k < CLUSTER_COUNT; k++) {
          if (counts[k] > 0) {
            centroids[k] = [sums[k][0] / counts[k], sums[k][1] / counts[k], sums[k][2] / counts[k]];
          }
        }
      }

      // Pick the centroid with the most border pixels as background
      const clusterCounts = new Array(CLUSTER_COUNT).fill(0);
      for (const c of borderColors) {
        let minD = Infinity, minI = 0;
        for (let k = 0; k < CLUSTER_COUNT; k++) {
          const d = dist3(c, centroids[k]);
          if (d < minD) { minD = d; minI = k; }
        }
        clusterCounts[minI]++;
      }
      let bgCluster = 0;
      for (let k = 1; k < CLUSTER_COUNT; k++) {
        if (clusterCounts[k] > clusterCounts[bgCluster]) bgCluster = k;
      }
      const bgColor = centroids[bgCluster];

      // --- Step 2: Flood-fill from borders using local color comparison ---
      const bgMask = new Uint8Array(total);     // 1 = background
      const visited = new Uint8Array(total);
      const localDist = new Float32Array(total); // distance to local background

      const queue = [];
      // Seed from all border pixels
      for (let x = 0; x < w; x++) {
        queue.push(idx(x, 0));
        queue.push(idx(x, h - 1));
      }
      for (let y = 1; y < h - 1; y++) {
        queue.push(idx(0, y));
        queue.push(idx(w - 1, y));
      }

      // Adaptive threshold: use Euclidean distance, with generous tolerance
      const BG_THRESHOLD = 80; // Euclidean distance threshold for flood-fill
      const EDGE_THRESHOLD = 50; // Looser threshold for post-processing near edges

      let head = 0;
      while (head < queue.length) {
        const pi = queue[head++];
        if (pi < 0 || pi >= total || visited[pi]) continue;
        visited[pi] = 1;

        const byteIdx = pi * ch;
        const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
        const d = dist3(pxColor, bgColor);

        if (d > BG_THRESHOLD) continue;

        bgMask[pi] = 1;
        localDist[pi] = d;

        const x = pi % w;
        const y = (pi - x) / w;
        if (x > 0) queue.push(pi - 1);
        if (x < w - 1) queue.push(pi + 1);
        if (y > 0) queue.push(pi - w);
        if (y < h - 1) queue.push(pi + w);
      }

      // --- Step 3: Post-process — expand background near edges with looser threshold ---
      // For pixels adjacent to confirmed background, use a softer threshold
      const expanded = new Uint8Array(total);
      expanded.set(bgMask);

      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
          const i = idx(x, y);
          if (bgMask[i]) continue;

          // Check if any neighbor is background
          let hasBgNeighbor = false;
          if (x > 0 && bgMask[idx(x - 1, y)]) hasBgNeighbor = true;
          if (x < w - 1 && bgMask[idx(x + 1, y)]) hasBgNeighbor = true;
          if (y > 0 && bgMask[idx(x, y - 1)]) hasBgNeighbor = true;
          if (y < h - 1 && bgMask[idx(x, y + 1)]) hasBgNeighbor = true;

          if (hasBgNeighbor) {
            const byteIdx = i * ch;
            const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
            const d = dist3(pxColor, bgColor);
            if (d < EDGE_THRESHOLD) {
              expanded[i] = 1;
            }
          }
        }
      }

      // Second expansion pass for smoother edges
      const expanded2 = new Uint8Array(total);
      expanded2.set(expanded);
      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
          const i = idx(x, y);
          if (expanded[i]) continue;

          let bgCount = 0;
          if (x > 0 && expanded[idx(x - 1, y)]) bgCount++;
          if (x < w - 1 && expanded[idx(x + 1, y)]) bgCount++;
          if (y > 0 && expanded[idx(x, y - 1)]) bgCount++;
          if (y < h - 1 && expanded[idx(x, y + 1)]) bgCount++;
          // Diagonals
          if (x > 0 && y > 0 && expanded[idx(x - 1, y - 1)]) bgCount++;
          if (x < w - 1 && y > 0 && expanded[idx(x + 1, y - 1)]) bgCount++;
          if (x > 0 && y < h - 1 && expanded[idx(x - 1, y + 1)]) bgCount++;
          if (x < w - 1 && y < h - 1 && expanded[idx(x + 1, y + 1)]) bgCount++;

          if (bgCount >= 5) {
            const byteIdx = i * ch;
            const pxColor = [data[byteIdx], data[byteIdx + 1], data[byteIdx + 2]];
            const d = dist3(pxColor, bgColor);
            if (d < EDGE_THRESHOLD * 0.8) {
              expanded2[i] = 1;
            }
          }
        }
      }

      // --- Step 4: Apply mask with edge feathering ---
      for (let i = 0; i < total; i++) {
        if (expanded2[i]) {
          data[i * ch + 3] = 0;
        }
      }

      // Feather: smooth alpha at the boundary
      for (let y = 1; y < h - 1; y++) {
        for (let x = 1; x < w - 1; x++) {
          const i = idx(x, y);
          if (expanded2[i]) continue; // already background
          const byteIdx = i * ch;
          if (data[byteIdx + 3] === 0) continue;

          // Count background neighbors (including diagonals)
          let bgCount = 0;
          for (let dy = -1; dy <= 1; dy++) {
            for (let dx = -1; dx <= 1; dx++) {
              if (dx === 0 && dy === 0) continue;
              if (expanded2[idx(x + dx, y + dy)]) bgCount++;
            }
          }

          if (bgCount > 0) {
            // Smooth alpha: more bg neighbors = more transparent
            const alpha = Math.max(0, Math.round(255 * (1 - bgCount / 8)));
            data[byteIdx + 3] = Math.min(data[byteIdx + 3], alpha);
          }
        }
      }

      await sharp(data, { raw: { width: w, height: h, channels: 4 } })
        .png()
        .toFile(outputPath);

      res.json({
        ok: true,
        message: "抠图成功",
        data: { imageUrl: `/uploads/${outputFilename}` }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createAssetsRouter };
