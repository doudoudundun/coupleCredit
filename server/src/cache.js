/**
 * In-memory TTL cache for frequently accessed data.
 * Keys are strings, values are any JS value with a TTL in seconds.
 */
class MemoryCache {
  constructor(maxSize = 500) {
    this.store = new Map();
    this.maxSize = maxSize;
    this._sweepInterval = setInterval(() => this._sweep(), 60000);
    // 缓存清理不应阻止 CLI 测试或优雅退出结束进程。
    this._sweepInterval.unref?.();
  }

  get(key) {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (Date.now() > entry.expiresAt) {
      this.store.delete(key);
      return null;
    }
    return entry.value;
  }

  set(key, value, ttlSeconds) {
    if (this.store.size >= this.maxSize) {
      const oldestKey = this.store.keys().next().value;
      this.store.delete(oldestKey);
    }
    this.store.set(key, {
      value,
      expiresAt: Date.now() + ttlSeconds * 1000
    });
  }

  del(key) {
    this.store.delete(key);
  }

  delPrefix(prefix) {
    for (const key of this.store.keys()) {
      if (key.startsWith(prefix)) this.store.delete(key);
    }
  }

  _sweep() {
    const now = Date.now();
    for (const [key, entry] of this.store) {
      if (now > entry.expiresAt) this.store.delete(key);
    }
  }
}

const cache = new MemoryCache();

// Cache key helpers
const Keys = {
  coupleRole: (userId) => `couple:role:${userId}`,
  relationship: (userId) => `couple:rel:${userId}`,
  profile: (userId) => `user:profile:${userId}`,
  bills: (userId, year, month) => `bills:${userId}:${year}-${month}`,
  // 月报历史基线聚合（RETENTION_MONTHLY_SPEC_20261003 §6.2）。key 必须落在 `bills:${userId}:`
  // 前缀下，账单写路径（invalidateBillCaches 等）统一 delPrefix 该前缀，monthly 缓存随之失效。
  billsMonthly: (userId, months, endYm, scope = "shared") => `bills:${userId}:monthly:${scope}:${months}${endYm ? `:${endYm}` : ""}`,
  inventory: (userId) => `inventory:${userId}`,
  beads: (userId) => `beads:${userId}`,
  beadBlueprints: (userId) => `bead-blueprints:${userId}`,
  recipes: (userId) => `recipes:${userId}`,
  recipeRecommend: (userId) => `recipe-rec:${userId}`,
  recipeCategories: (userId) => `recipe-cats:${userId}`,
  sharedPlans: (userId) => `shared-plans:${userId}`,
  todos: (userId) => `todos:${userId}`,
  restaurants: (userId) => `restaurants:${userId}`,
  calorieToday: (userId) => `calorie:today:${userId}`,
  calorieHistory: (userId, date) => `calorie:history:${userId}:${date}`,
  calorieHistoryByUser: (userId) => `calorie:history:${userId}:`,
  nutritionSearch: (query) => `nutrition:search:${query}`,
  assets: (userId) => `assets:${userId}`,
  assetStats: (userId) => `asset-stats:${userId}`,
  assetCategories: (userId) => `asset-cats:${userId}`,
  passwordAccounts: (userId) => `pwd-accounts:${userId}`,
  passwordAccountItem: (id) => `pwd-account:${id}`,
  passwordAccountCategories: (userId) => `pwd-account-cats:${userId}`,
  overview: (userId, year, month) => `overview:${userId}:${year}-${month}`,
};

// TTL constants (seconds)
const TTL = {
  ROLE: 300,
  PROFILE: 60,
  BILLS: 15,
  BILLS_MONTHLY: 15,
  REL: 300,
  INVENTORY: 15,
  BEADS: 15,
  RECIPES: 30,
  RECIPE_RECOMMEND: 30,
  RECIPE_CATS: 60,
  SHARED_PLANS: 15,
  TODOS: 15,
  RESTAURANTS: 30,
  CALORIE: 15,
  NUTRITION: 300,
  ASSETS: 15,
  ASSET_CATS: 60,
  PASSWORD_ACCOUNTS: 15,
  PASSWORD_ACCOUNT_ITEM: 15,
  PASSWORD_ACCOUNT_CATS: 60,
  OVERVIEW: 10,
};

module.exports = { cache, Keys, TTL };
