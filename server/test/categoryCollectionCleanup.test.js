const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const bcrypt = require("bcrypt");
const {
  cleanCategoryCollectionsForAccountDeletion,
  archiveSharedCategoryCollectionsForAccountDeletion
} = require("../src/utils/categoryCollectionCleanup");
const { createAuthRouter } = require("../src/routes/auth");
const { createCategoryCollectionsRouter } = require("../src/routes/categoryCollections");

const CHILD_TABLES = [
  "item_template_preferences",
  "collection_mutations",
  "collection_audits",
  "preset_bindings",
  "item_templates",
  "item_categories"
];

function collectionFixture() {
  const collections = [
    { id: "personal-a", scope: "personal", owner_user_id: 4, relationship_id: null },
    { id: "shared-ab", scope: "couple", owner_user_id: null, relationship_id: 9 },
    { id: "personal-b", scope: "personal", owner_user_id: 5, relationship_id: null }
  ];
  const children = new Map();
  for (const collection of collections) {
    for (const table of CHILD_TABLES) children.set(`${table}:${collection.id}`, 1);
  }
  const deletedChildTables = [];
  const deletedPreferenceUsers = [];
  const preferences = [
    { user_id: 4, collection_id: "personal-a" },
    // Legacy rows can belong to another user while still referencing this
    // personal collection, so account-wide preference cleanup is insufficient.
    { user_id: 5, collection_id: "personal-a" },
    { user_id: 5, collection_id: "shared-ab" },
    { user_id: 5, collection_id: "personal-b" }
  ];

  return {
    collections,
    children,
    preferences,
    deletedChildTables,
    deletedPreferenceUsers,
    async execute(sql, params) {
      if (sql === "DELETE FROM item_template_preferences WHERE user_id = ?") {
        deletedPreferenceUsers.push(params[0]);
        for (let index = preferences.length - 1; index >= 0; index -= 1) {
          if (preferences[index].user_id === params[0]) preferences.splice(index, 1);
        }
        return [{ affectedRows: 2 }];
      }
      if (sql.includes("FROM category_collections") && sql.includes("FOR UPDATE")) {
        assert.match(sql, /scope = 'personal'/);
        assert.match(sql, /owner_user_id = \?/);
        assert.match(sql, /relationship_id IS NULL/);
        const rows = collections
          .filter((row) => row.scope === "personal" && row.owner_user_id === params[0] && row.relationship_id === null)
          .map(({ id }) => ({ id }));
        return [rows];
      }

      const childDelete = sql.match(/^DELETE FROM (\w+) WHERE collection_id = \?$/);
      if (childDelete) {
        const [, table] = childDelete;
        const collectionId = params[0];
        deletedChildTables.push({ table, collectionId });
        if (table === "item_template_preferences") {
          for (let index = preferences.length - 1; index >= 0; index -= 1) {
            if (preferences[index].collection_id === collectionId) preferences.splice(index, 1);
          }
        }
        children.delete(`${table}:${collectionId}`);
        return [{ affectedRows: 1 }];
      }

      if (sql.includes("DELETE FROM category_collections")) {
        assert.match(sql, /scope = 'personal'/);
        assert.match(sql, /owner_user_id = \?/);
        assert.match(sql, /relationship_id IS NULL/);
        const [collectionId, userId] = params;
        assert.equal(preferences.some((row) => row.collection_id === collectionId), false,
          "collection preferences must be removed before the referenced collection");
        const index = collections.findIndex((row) => row.id === collectionId && row.scope === "personal"
          && row.owner_user_id === userId && row.relationship_id === null);
        if (index < 0) return [{ affectedRows: 0 }];
        collections.splice(index, 1);
        return [{ affectedRows: 1 }];
      }

      throw new Error(`Unexpected SQL: ${sql}`);
    }
  };
}

test("account collection cleanup removes only the deleting user's personal tree", async () => {
  const fixture = collectionFixture();

  const removedCount = await cleanCategoryCollectionsForAccountDeletion(fixture, 4);

  assert.equal(removedCount, 1);
  assert.deepEqual(fixture.deletedPreferenceUsers, [4]);
  assert.deepEqual(fixture.collections.map(({ id }) => id), ["shared-ab", "personal-b"]);
  assert.equal(fixture.children.has("item_categories:personal-a"), false);
  assert.equal(fixture.children.has("item_categories:shared-ab"), true);
  assert.equal(fixture.children.has("item_categories:personal-b"), true);
  assert.deepEqual(fixture.deletedChildTables, CHILD_TABLES.map((table) => ({ table, collectionId: "personal-a" })));
  assert.deepEqual(fixture.preferences.map(({ user_id, collection_id }) => ({ user_id, collection_id })), [
    { user_id: 5, collection_id: "shared-ab" },
    { user_id: 5, collection_id: "personal-b" }
  ]);
});

test("account collection cleanup is a no-op when the user has no personal collections", async () => {
  const fixture = collectionFixture();

  assert.equal(await cleanCategoryCollectionsForAccountDeletion(fixture, 999), 0);
  assert.deepEqual(fixture.deletedPreferenceUsers, [999]);
  assert.equal(fixture.deletedChildTables.length, 0);
  assert.equal(fixture.collections.length, 3);
});

test("shared collection archival preserves entity IDs and audits the original relationship members", async () => {
  const collections = [
    { id: "shared-a", relationship_id: 9, domain: "inventory", direction: null, status: "active", version: 3 },
    { id: "shared-b", relationship_id: 9, domain: "bills", direction: "expense", status: "closed", version: 5 },
    { id: "shared-other", relationship_id: 10, domain: "inventory", direction: null, status: "active", version: 1 }
  ];
  const audits = [];
  const conn = {
    async execute(sql, params) {
      if (sql.includes("FROM category_collections") && sql.includes("FOR UPDATE")) {
        assert.match(sql, /scope = 'couple'/);
        return [collections.filter((row) => row.relationship_id === params[0]).map((row) => ({ ...row }))];
      }
      if (sql.startsWith("UPDATE category_collections")) {
        const [survivor, id, relationshipId] = params;
        const row = collections.find((item) => item.id === id && item.relationship_id === relationshipId);
        if (!row) return [{ affectedRows: 0 }];
        row.status = "closed";
        row.relationship_id = null;
        row.archived_for_user_id = survivor;
        row.version += 1;
        return [{ affectedRows: 1 }];
      }
      if (sql.startsWith("INSERT INTO collection_audits")) {
        audits.push(params);
        return [{ affectedRows: 1 }];
      }
      throw new Error(`Unexpected SQL: ${sql}`);
    }
  };

  assert.equal(await archiveSharedCategoryCollectionsForAccountDeletion(conn, 4, [
    { relationship_id: 9, user_id_1: 4, user_id_2: 5 }
  ]), 2);
  assert.deepEqual(collections.map(({ id, relationship_id, archived_for_user_id, status, version }) => ({
    id, relationship_id, archived_for_user_id, status, version
  })), [
    { id: "shared-a", relationship_id: null, archived_for_user_id: 5, status: "closed", version: 4 },
    { id: "shared-b", relationship_id: null, archived_for_user_id: 5, status: "closed", version: 6 },
    { id: "shared-other", relationship_id: 10, archived_for_user_id: undefined, status: "active", version: 1 }
  ]);
  assert.equal(audits.length, 2);
  for (const audit of audits) {
    assert.equal(audit[1], 4);
    assert.equal(audit[2], audit[0]);
    assert.deepEqual(JSON.parse(audit[3]).memberUserIds, [4, 5]);
    assert.equal(JSON.parse(audit[3]).relationshipId, 9);
    assert.equal(JSON.parse(audit[4]).archivedForUserId, 5);
  }
});

test("only the surviving member can read an archived shared collection and writes are rejected", async () => {
  const archivedCollection = {
    id: "02000000-0000-4000-8000-000000000001", domain: "inventory", direction: null, scope: "couple", status: "closed",
    owner_user_id: null, relationship_id: null, archived_for_user_id: 5, version: 4,
    relationship_status: null, rel_u1: null, rel_u2: null
  };
  const lockCalls = [];
  const conn = {
    async execute(sql, params) {
      lockCalls.push({ sql, params });
      if (sql === "SELECT id FROM users WHERE id = ? FOR UPDATE") return [[{ id: 5 }]];
      if (sql.includes("FROM category_collections c")) return [[archivedCollection]];
      if (sql.includes("FROM item_categories")) return [[{
        id: "01000000-0000-4000-8000-000000000001", collection_id: "02000000-0000-4000-8000-000000000001", name: "旧物资", color: "#112233",
        sort_order: 0, status: "active", version: 1, source_preset_key: null
      }]];
      throw new Error(`Unexpected SQL: ${sql}`);
    },
    async beginTransaction() {}, async commit() {}, async rollback() {}, release() {}
  };
  const pool = { async getConnection() { return conn; } };
  const router = createCategoryCollectionsRouter({ pool });
  const getHandler = router.stack.find((entry) => entry.route?.path === "/category-collections/:id/categories"
    && entry.route.methods.get).route.stack.at(-1).handle;
  const patchHandler = router.stack.find((entry) => entry.route?.path === "/category-collections/:id/categories/:categoryId"
    && entry.route.methods.patch).route.stack.at(-1).handle;

  const get = async (userId) => {
    let body, error;
    await getHandler({ userId, params: { id: "02000000-0000-4000-8000-000000000001" }, query: { status: "all" } }, {
      json(value) { body = value; return this; }
    }, (value) => { error = value; });
    return { body, error };
  };
  const survivorRead = await get(5);
  assert.equal(survivorRead.error, undefined);
  assert.equal(survivorRead.body.data.canWrite, false);
  assert.equal(survivorRead.body.data.items[0].categoryId, "01000000-0000-4000-8000-000000000001");
  const strangerRead = await get(8);
  assert.equal(strangerRead.body, undefined);
  assert.equal(strangerRead.error?.code, "RESOURCE_NOT_FOUND");

  let writeError;
  const beforeWriteCalls = lockCalls.length;
  await patchHandler({ userId: 5, params: { id: "02000000-0000-4000-8000-000000000001", categoryId: "01000000-0000-4000-8000-000000000001" }, query: {},
    body: { clientMutationId: "03000000-0000-4000-8000-000000000001", expectedVersion: 1, name: "不应成功" } }, {},
  (value) => { writeError = value; });
  assert.equal(writeError?.code, "RELATIONSHIP_CHANGED");
  assert.equal(lockCalls[beforeWriteCalls].sql, "SELECT id FROM users WHERE id = ? FOR UPDATE");
  assert.match(lockCalls[beforeWriteCalls + 1].sql, /FROM category_collections c[\s\S]*FOR UPDATE/);
});

test("migration 034 adds the archive ACL idempotently without a users foreign key", () => {
  const sql = fs.readFileSync(path.resolve(__dirname, "../migrations/034_archive_category_collections_on_account_delete.sql"), "utf8");
  assert.match(sql, /INFORMATION_SCHEMA\.COLUMNS/);
  assert.match(sql, /archived_for_user_id/);
  assert.match(sql, /INFORMATION_SCHEMA\.STATISTICS/);
  assert.match(sql, /idx_cc_archived_user/);
  assert.doesNotMatch(sql, /FOREIGN KEY\s*\(\s*archived_for_user_id\s*\)\s*REFERENCES\s+users/i);
  assert.doesNotMatch(sql, /FOREIGN_KEY_CHECKS\s*=\s*0/i);
});

test("account deletion runs personal category cleanup before deleting the user", async () => {
  const passwordHash = bcrypt.hashSync("secret123", 4);
  const state = {
    userExists: true,
    personalCollectionExists: true,
    childRows: Object.fromEntries(CHILD_TABLES.map((table) => [table, 1]))
  };
  const calls = [];

  const execute = async (sql, params = []) => {
    calls.push({ sql, params });
    if (sql === "DELETE FROM item_template_preferences WHERE user_id = ?") return [{ affectedRows: 1 }];
    if (sql.startsWith("SELECT relationship_id") && sql.includes("FROM couple_relationships")) return [[]];
    if (sql.startsWith("SELECT id FROM users")) return [[{ id: params[0] }]];
    if (sql.startsWith("SELECT password FROM users")) return [[{ password: passwordHash }]];
    if (sql.includes("FROM housework_spaces WHERE scope = 'personal'")) return [[]];
    if (sql.includes("FROM category_collections") && sql.includes("FOR UPDATE")) {
      assert.match(sql, /scope = 'personal'/);
      assert.match(sql, /owner_user_id = \?/);
      assert.match(sql, /relationship_id IS NULL/);
      return [state.personalCollectionExists ? [{ id: "personal-a" }] : []];
    }

    const childDelete = sql.match(/^DELETE FROM (\w+) WHERE collection_id = \?$/);
    if (childDelete) {
      state.childRows[childDelete[1]] = 0;
      return [{ affectedRows: 1 }];
    }
    if (sql.includes("DELETE FROM category_collections")) {
      state.personalCollectionExists = false;
      return [{ affectedRows: 1 }];
    }
    if (sql.startsWith("DELETE FROM users")) {
      state.userExists = false;
      return [{ affectedRows: 1 }];
    }
    if (sql.startsWith("DELETE FROM couple_relationships") || sql.startsWith("DELETE FROM housework_preferences")
      || sql.startsWith("UPDATE housework_") || sql.startsWith("UPDATE inventory")
      || sql.startsWith("UPDATE bills") || sql.startsWith("UPDATE users")) {
      return [{ affectedRows: 1 }];
    }
    throw new Error(`Unexpected SQL: ${sql}`);
  };
  let committed = false;
  let rolledBack = false;
  const conn = {
    execute,
    async beginTransaction() {},
    async commit() { committed = true; },
    async rollback() { rolledBack = true; },
    release() {}
  };
  const pool = {
    execute,
    async getConnection() { return conn; }
  };
  const pass = (_req, _res, next) => next();
  const router = createAuthRouter({
    pool,
    config: { jwtSecret: "test-secret-long-enough-for-sessions" },
    authLimiter: pass,
    strictLimiter: pass,
    requireAuth: pass
  });
  const route = router.stack.find((entry) => entry.route?.path === "/account" && entry.route.methods.delete).route;
  let response;
  let error;
  await route.stack.at(-1).handle(
    { userId: 4, body: { password: "secret123" }, params: {}, query: {} },
    { json(value) { response = value; return this; } },
    (value) => { error = value; }
  );

  assert.equal(error, undefined);
  assert.equal(response?.ok, true);
  assert.equal(state.userExists, false);
  assert.equal(state.personalCollectionExists, false);
  assert.ok(Object.values(state.childRows).every((count) => count === 0));
  const preferenceDeleteIndex = calls.findIndex(({ sql, params }) =>
    sql === "DELETE FROM item_template_preferences WHERE collection_id = ?" && params[0] === "personal-a");
  const templateDeleteIndex = calls.findIndex(({ sql }) => sql.startsWith("DELETE FROM item_templates"));
  assert.ok(preferenceDeleteIndex >= 0 && preferenceDeleteIndex < templateDeleteIndex,
    "owned collection preferences must be deleted before template children");
  const collectionDeleteIndex = calls.findIndex(({ sql }) => sql.includes("DELETE FROM category_collections"));
  const userDeleteIndex = calls.findIndex(({ sql }) => sql.startsWith("DELETE FROM users"));
  assert.ok(collectionDeleteIndex >= 0 && collectionDeleteIndex < userDeleteIndex);
  assert.equal(committed, true);
  assert.equal(rolledBack, false);
});
