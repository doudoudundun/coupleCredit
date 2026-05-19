const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger, invalidateForUser } = require("../utils/queryHelpers");

function createRestaurantRouter({ pool }) {
  const router = express.Router();

  function invalidateCache(userId, relationship) {
    invalidateForUser(cache, Keys.restaurants, userId, relationship);
  }

  // GET /api/restaurants?userId=
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));
      const cached = cache.get(Keys.restaurants(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query, params;
      if (relationshipId) {
        query = `SELECT restaurant_id, user_id, relationship_id, name, category, image_url, route_image_url, avg_cost, default_calories, distance, address, note, created_at, updated_at
                 FROM restaurants WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL) ORDER BY updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT restaurant_id, user_id, relationship_id, name, category, image_url, route_image_url, avg_cost, default_calories, distance, address, note, created_at, updated_at
                 FROM restaurants WHERE user_id = ? AND relationship_id IS NULL ORDER BY updated_at DESC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(r => ({
        restaurantId: r.restaurant_id,
        userId: r.user_id,
        relationshipId: r.relationship_id,
        name: r.name,
        category: r.category,
        imageUrl: r.image_url,
        routeImageUrl: r.route_image_url,
        avgCost: r.avg_cost !== null ? Number(r.avg_cost) : null,
        defaultCalories: r.default_calories !== null ? Number(r.default_calories) : null,
        distance: r.distance !== null ? Number(r.distance) : null,
        address: r.address,
        note: r.note,
        createdAt: r.created_at,
        updatedAt: r.updated_at
      }));

      const responseData = { ok: true, data: { items, relationshipId } };
      cache.set(Keys.restaurants(userId), responseData, TTL.RESTAURANTS);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  // POST /api/restaurants
  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      if (!name) throw new ApiError(400, "INVALID_REQUEST", "商家名称不能为空");

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const category = trimValue(req.body.category) || null;
      const imageUrl = req.body.imageUrl || null;
      const routeImageUrl = req.body.routeImageUrl || null;
      const avgCost = req.body.avgCost != null ? Number(req.body.avgCost) : null;
      const defaultCalories = req.body.defaultCalories != null ? Number(req.body.defaultCalories) : null;
      const distance = req.body.distance != null ? Number(req.body.distance) : null;
      const address = trimValue(req.body.address) || null;
      const note = trimValue(req.body.note) || null;

      const [result] = await pool.execute(
        `INSERT INTO restaurants (user_id, relationship_id, name, category, image_url, route_image_url, avg_cost, default_calories, distance, address, note, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())`,
        [userId, relationshipId, name, category, imageUrl, routeImageUrl, avgCost, defaultCalories, distance, address, note]
      );

      invalidateCache(userId, relationship);
      res.status(201).json({
        ok: true,
        data: {
          restaurantId: result.insertId,
          userId, relationshipId, name, category, imageUrl, routeImageUrl, avgCost, defaultCalories, distance, address, note
        }
      });
    } catch (error) { next(error); }
  });

  // PUT /api/restaurants/:id
  router.put("/:id", async (req, res, next) => {
    try {
      const restaurantId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(req.body.userId);
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery, checkParams;
      if (relationshipId) {
        checkQuery = "SELECT restaurant_id FROM restaurants WHERE restaurant_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [restaurantId, relationshipId, userId];
      } else {
        checkQuery = "SELECT restaurant_id FROM restaurants WHERE restaurant_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [restaurantId, userId];
      }
      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) throw new ApiError(404, "NOT_FOUND", "商家不存在或无权修改");

      const updates = [];
      const params = [];

      if (req.body.name !== undefined) { updates.push("name = ?"); params.push(trimValue(req.body.name)); }
      if (req.body.category !== undefined) { updates.push("category = ?"); params.push(trimValue(req.body.category) || null); }
      if (req.body.imageUrl !== undefined) { updates.push("image_url = ?"); params.push(req.body.imageUrl || null); }
      if (req.body.routeImageUrl !== undefined) { updates.push("route_image_url = ?"); params.push(req.body.routeImageUrl || null); }
      if (req.body.avgCost !== undefined) { updates.push("avg_cost = ?"); params.push(req.body.avgCost != null ? Number(req.body.avgCost) : null); }
      if (req.body.defaultCalories !== undefined) { updates.push("default_calories = ?"); params.push(req.body.defaultCalories != null ? Number(req.body.defaultCalories) : null); }
      if (req.body.distance !== undefined) { updates.push("distance = ?"); params.push(req.body.distance != null ? Number(req.body.distance) : null); }
      if (req.body.address !== undefined) { updates.push("address = ?"); params.push(trimValue(req.body.address) || null); }
      if (req.body.note !== undefined) { updates.push("note = ?"); params.push(trimValue(req.body.note) || null); }

      if (updates.length === 0) throw new ApiError(400, "INVALID_REQUEST", "没有提供要更新的字段");

      updates.push("updated_at = NOW()");
      params.push(restaurantId);
      await pool.execute(`UPDATE restaurants SET ${updates.join(", ")} WHERE restaurant_id = ?`, params);

      invalidateCache(userId, relationship);
      res.json({ ok: true, message: "商家更新成功" });
    } catch (error) { next(error); }
  });

  // DELETE /api/restaurants/:id?userId=
  router.delete("/:id", async (req, res, next) => {
    try {
      const restaurantId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(Number(req.query.userId));
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let deleteQuery, deleteParams;
      if (relationshipId) {
        deleteQuery = "DELETE FROM restaurants WHERE restaurant_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        deleteParams = [restaurantId, relationshipId, userId];
      } else {
        deleteQuery = "DELETE FROM restaurants WHERE restaurant_id = ? AND user_id = ? AND relationship_id IS NULL";
        deleteParams = [restaurantId, userId];
      }

      const [result] = await pool.execute(deleteQuery, deleteParams);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "商家不存在或无权删除");

      invalidateCache(userId, relationship);
      res.json({ ok: true, message: "商家删除成功" });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRestaurantRouter };
