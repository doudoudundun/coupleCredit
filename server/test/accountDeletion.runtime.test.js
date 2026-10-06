const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const test = require("node:test");
const mysql = require("mysql2/promise");
const { readConfig } = require("../src/config");
const { archiveSharedCategoryCollectionsForAccountDeletion } = require("../src/utils/categoryCollectionCleanup");
const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");

const baseUrl = process.env.AUTH_API_BASE_URL || "";
const enabled = /^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl);
let httpRequests = 0;
let assertions = 0;
for (const method of ["equal", "ok", "deepEqual"]) {
  const original = assert[method];
  assert[method] = function (...args) {
    assertions += 1;
    return original.apply(this, args);
  };
}

async function jsonResponse(response) {
  const raw = await response.text();
  try { return JSON.parse(raw); } catch (_error) { return raw; }
}

async function callApi(user, method, path, body) {
  httpRequests += 1;
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(user?.accessToken ? { Authorization: `Bearer ${user.accessToken}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: response.status, body: await jsonResponse(response) };
}

function uniqueName(role, suffix) {
  let name = "";
  for (let attempt = 0; attempt < 20 && !name; attempt += 1) {
    const candidate = `ccqa${role}${suffix}${crypto.randomBytes(2).toString("hex")}`;
    if (!TEXT_PROMO_PATTERNS.some((pattern) => pattern.re.test(candidate))) name = candidate;
  }
  if (!name) throw new Error("could not generate a test username accepted by the local content filter");
  return name;
}

async function createUser(role, suffix, inviteCode) {
  const username = uniqueName(role, suffix);
  const password = "secret123";
  const registration = await callApi(null, "POST", "/api/auth/register", {
    username, email: `${username}@example.com`, password, inviteCode
  });
  assert.equal(registration.status, 201, JSON.stringify(registration.body));
  const login = await callApi(null, "POST", "/api/auth/login", { username, password });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  return { ...login.body.data, password };
}

async function getCollection(user, domain, scope, direction = null) {
  const result = await callApi(user, "GET", `/api/category-collections?domain=${domain}`);
  assert.equal(result.status, 200, JSON.stringify(result.body));
  const collection = result.body.data.collections.find((row) => row.scope === scope
    && (direction === null || row.direction === direction));
  assert.ok(collection, `missing ${scope} ${domain} ${direction || ""} collection`);
  return collection;
}

async function getCategories(user, collectionId) {
  const result = await callApi(user, "GET", `/api/category-collections/${collectionId}/categories?status=all&limit=50`);
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body.data;
}

async function withRollback(connection, callback) {
  await connection.beginTransaction();
  try {
    const value = await callback();
    await connection.commit();
    return value;
  } catch (error) {
    await connection.rollback();
    throw error;
  }
}

async function proveWriteLocksActorBeforeCollection({ user, category, collectionId, dbConfig, suffix }) {
  const actorBlocker = await mysql.createConnection(dbConfig);
  const categoryBlocker = await mysql.createConnection(dbConfig);
  const collectionProbe = await mysql.createConnection(dbConfig);
  let pendingWrite;
  let blockerTransactions = false;
  try {
    await actorBlocker.beginTransaction();
    await actorBlocker.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [user.userId]);
    await categoryBlocker.beginTransaction();
    await categoryBlocker.execute("SELECT id FROM item_categories WHERE id = ? FOR UPDATE", [category.categoryId]);
    blockerTransactions = true;

    pendingWrite = callApi(user, "PATCH", `/api/category-collections/${collectionId}/categories/${category.categoryId}`, {
      clientMutationId: crypto.randomUUID(),
      expectedVersion: category.version,
      name: `lock-${suffix}`
    });

    let actorWaitObserved = false;
    for (let attempt = 0; attempt < 40 && !actorWaitObserved; attempt += 1) {
      const [processes] = await collectionProbe.query(
        `SELECT INFO FROM INFORMATION_SCHEMA.PROCESSLIST
         WHERE DB = DATABASE() AND INFO LIKE '%SELECT id FROM users WHERE id =%FOR UPDATE%'`
      );
      actorWaitObserved = processes.length > 0;
      if (!actorWaitObserved) await new Promise((resolve) => setTimeout(resolve, 50));
    }
    assert.equal(actorWaitObserved, true, "API write did not wait on the actor user row");

    await collectionProbe.query("SET SESSION innodb_lock_wait_timeout = 1");
    await withRollback(collectionProbe, async () => {
      const [rows] = await collectionProbe.execute("SELECT id FROM category_collections WHERE id = ? FOR UPDATE", [collectionId]);
      assert.equal(rows.length, 1);
    });
  } finally {
    if (blockerTransactions) {
      await categoryBlocker.rollback();
      await actorBlocker.rollback();
    }
    await categoryBlocker.end();
    await actorBlocker.end();
    await collectionProbe.end();
  }
  const writeResult = await pendingWrite;
  assert.equal(writeResult.status, 200, JSON.stringify(writeResult.body));
  return writeResult;
}

async function collectExactFixtureCollections(db, state) {
  const userIds = [...new Set(state.users.map((user) => Number(user.userId)).filter(Number.isSafeInteger))];
  const relationshipIds = [...new Set(state.relationshipIds.map(Number).filter(Number.isSafeInteger))];
  const clauses = [];
  const params = [];
  if (userIds.length > 0) {
    const marks = userIds.map(() => "?").join(",");
    clauses.push(`owner_user_id IN (${marks})`);
    clauses.push(`archived_for_user_id IN (${marks})`);
    params.push(...userIds, ...userIds);
  }
  if (relationshipIds.length > 0) {
    clauses.push(`relationship_id IN (${relationshipIds.map(() => "?").join(",")})`);
    params.push(...relationshipIds);
  }
  if (clauses.length === 0) return [];

  const [rows] = await db.execute(
    `SELECT id FROM category_collections WHERE ${clauses.map((clause) => `(${clause})`).join(" OR ")}`,
    params
  );
  for (const row of rows) state.collectionIds.add(String(row.id));
  return rows.map((row) => String(row.id));
}

async function cleanupExactFixtures(db, state) {
  await collectExactFixtureCollections(db, state);
  const userIds = [...new Set(state.users.map((user) => Number(user.userId)).filter(Number.isSafeInteger))];
  const collectionIds = [...state.collectionIds];
  const relationshipIds = [...new Set(state.relationshipIds.map(Number).filter(Number.isSafeInteger))];
  if (state.inventoryIds.length > 0) {
    await db.execute(`DELETE FROM inventory WHERE inventory_id IN (${state.inventoryIds.map(() => "?").join(",")})`, state.inventoryIds);
  }
  if (state.billIds.length > 0) {
    await db.execute(`DELETE FROM bills WHERE bill_id IN (${state.billIds.map(() => "?").join(",")})`, state.billIds);
  }
  if (collectionIds.length > 0) {
    const marks = collectionIds.map(() => "?").join(",");
    await db.execute(`DELETE FROM item_template_preferences WHERE collection_id IN (${marks})`, collectionIds);
    for (const table of ["collection_mutations", "collection_audits", "preset_bindings", "item_templates", "item_categories"]) {
      await db.execute(`DELETE FROM ${table} WHERE collection_id IN (${marks})`, collectionIds);
    }
    await db.execute(`DELETE FROM category_collections WHERE id IN (${marks})`, collectionIds);
  }
  if (userIds.length > 0) {
    await db.execute(`DELETE FROM item_template_preferences WHERE user_id IN (${userIds.map(() => "?").join(",")})`, userIds);
  }
  const cycleIds = [];
  const spaceIds = [];
  if (relationshipIds.length > 0 && userIds.length > 1) {
    const relMarks = relationshipIds.map(() => "?").join(",");
    const userMarks = userIds.map(() => "?").join(",");
    const [cycles] = await db.execute(
      `SELECT cycle_id, ended_at FROM housework_relationship_cycles
       WHERE relationship_id IN (${relMarks})
         AND member_user_id_1 IN (${userMarks}) AND member_user_id_2 IN (${userMarks})`,
      [...relationshipIds, ...userIds, ...userIds]
    );
    for (const cycle of cycles) {
      assert.ok(cycle.ended_at, `fixture housework cycle ${cycle.cycle_id} must be closed before cleanup`);
      cycleIds.push(cycle.cycle_id);
    }
    if (cycleIds.length > 0) {
      const cycleMarks = cycleIds.map(() => "?").join(",");
      const [spaces] = await db.execute(
        `SELECT space_id, status FROM housework_spaces WHERE cycle_id IN (${cycleMarks}) AND scope = 'couple'`,
        cycleIds
      );
      for (const space of spaces) {
        assert.equal(space.status, "closed", `fixture housework space ${space.space_id} must be closed before cleanup`);
        spaceIds.push(space.space_id);
      }
      if (spaceIds.length > 0) {
        const spaceMarks = spaceIds.map(() => "?").join(",");
        await db.execute(
          `DELETE p FROM housework_record_participants p
           JOIN housework_records r ON r.record_id = p.record_id WHERE r.space_id IN (${spaceMarks})`,
          spaceIds
        );
        for (const table of ["housework_record_revisions", "housework_mutations", "housework_config_revisions",
          "housework_records", "housework_preferences", "housework_templates", "housework_categories", "housework_space_members"]) {
          await db.execute(`DELETE FROM ${table} WHERE space_id IN (${spaceMarks})`, spaceIds);
        }
        await db.execute(`DELETE FROM housework_spaces WHERE space_id IN (${spaceMarks}) AND status = 'closed'`, spaceIds);
      }
      await db.execute(`DELETE FROM housework_relationship_cycles WHERE cycle_id IN (${cycleMarks}) AND ended_at IS NOT NULL`, cycleIds);
    }
  }
  if (relationshipIds.length > 0) {
    await db.execute(`DELETE FROM couple_relationships WHERE relationship_id IN (${relationshipIds.map(() => "?").join(",")})`, relationshipIds);
  }
  return { cycleIds, spaceIds };
}

test("account deletion preserves shared history, survivor data, and lock order over local HTTP", { skip: !enabled }, async (t) => {
  const config = readConfig();
  assert.equal(config.dbHost, "127.0.0.1", "runtime regression must use the local database");
  assert.equal(config.dbName, "couple_credit_private", "runtime regression must use the private local database");
  const dbConfig = {
    host: config.dbHost, port: config.dbPort, user: config.dbUser,
    password: config.dbPassword, database: config.dbName
  };
  const db = await mysql.createConnection(dbConfig);
  const state = { users: [], collectionIds: new Set(), relationshipIds: [], inventoryIds: [], billIds: [] };
  const suffix = crypto.randomBytes(3).toString("hex");
  const requestStart = httpRequests;
  const assertionStart = assertions;
  let cleanedHousework = { cycleIds: [], spaceIds: [] };
  const now = new Date();
  const date = now.toISOString().slice(0, 10);
  let resultFixture = {};
  try {
    const solo = await createUser("s", suffix, config.inviteCode);
    state.users.push(solo);
    const departing = await createUser("a", suffix, config.inviteCode);
    state.users.push(departing);
    const survivor = await createUser("b", suffix, config.inviteCode);
    state.users.push(survivor);
    const newPartner = await createUser("c", suffix, config.inviteCode);
    state.users.push(newPartner);

    const soloInventory = await getCollection(solo, "inventory", "personal");
    state.collectionIds.add(soloInventory.collectionId);

    const invite = await callApi(departing, "POST", "/api/couple/generate-invite", {});
    assert.equal(invite.status, 200, JSON.stringify(invite.body));
    const bind = await callApi(survivor, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
    assert.equal(bind.status, 200, JSON.stringify(bind.body));

    const [relationshipRows] = await db.execute(
      "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE (user_id_1 = ? AND user_id_2 = ?) OR (user_id_1 = ? AND user_id_2 = ?)",
      [departing.userId, survivor.userId, survivor.userId, departing.userId]
    );
    assert.equal(relationshipRows.length, 1);
    const relationship = relationshipRows[0];
    const relationshipId = Number(relationship.relationship_id);
    state.relationshipIds.push(relationshipId);

    const departingPersonal = await getCollection(departing, "inventory", "personal");
    const sharedInventory = await getCollection(departing, "inventory", "couple");
    state.collectionIds.add(departingPersonal.collectionId);
    state.collectionIds.add(sharedInventory.collectionId);
    const survivorSharedInventory = await getCollection(survivor, "inventory", "couple");
    assert.equal(survivorSharedInventory.collectionId, sharedInventory.collectionId);

    const createdCategory = await callApi(survivor, "POST", `/api/category-collections/${sharedInventory.collectionId}/categories`, {
      clientMutationId: crypto.randomUUID(), name: `qa-${suffix}`
    });
    assert.equal(createdCategory.status, 200, JSON.stringify(createdCategory.body));
    const category = createdCategory.body.data.entity;

    const createdTemplate = await callApi(survivor, "POST", `/api/category-collections/${sharedInventory.collectionId}/templates`, {
      clientMutationId: crypto.randomUUID(), categoryId: category.categoryId,
      name: `retained-${suffix}`, defaultUnit: "个", suggestedAlertLine: "1.00"
    });
    assert.equal(createdTemplate.status, 200, JSON.stringify(createdTemplate.body));
    const template = createdTemplate.body.data.entity;

    for (const user of [survivor, departing]) {
      const preference = await callApi(user, "PATCH", `/api/category-collections/${sharedInventory.collectionId}/preferences`, {
        clientMutationId: crypto.randomUUID(), expectedVersion: 1, orderedTemplateIds: [template.templateId]
      });
      assert.equal(preference.status, 200, JSON.stringify(preference.body));
    }

    await db.execute(
      `INSERT INTO item_template_preferences
         (user_id, collection_id, ordered_template_ids, pinned_template_ids, hidden_template_ids)
       VALUES (?, ?, JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY())`,
      [survivor.userId, departingPersonal.collectionId]
    );

    const sharedBills = await getCollection(survivor, "bills", "couple", "expense");
    state.collectionIds.add(sharedBills.collectionId);
    const billCategories = await getCategories(survivor, sharedBills.collectionId);
    assert.ok(billCategories.items.length > 0, "seeded shared bills collection should expose a category");
    const billCategory = billCategories.items[0];

    const [inventoryResult] = await db.execute(
      `INSERT INTO inventory (user_id, relationship_id, name, category, quantity, unit, threshold, category_id)
       VALUES (?, ?, ?, 'QA', 3.50, '个', 1.00, ?)`,
      [survivor.userId, relationshipId, `qa-${suffix}`, category.categoryId]
    );
    state.inventoryIds.push(Number(inventoryResult.insertId));

    for (const owner of [1, 2, 3]) {
      const [billResult] = await db.execute(
        `INSERT INTO bills
           (relationship_id, owner, user_id, title, type, amount, date, time, income_type, is_help,
            category_id, category_name_snapshot, category_icon_snapshot)
         VALUES (?, ?, ?, ?, 'expense', 12.34, ?, '12:00:00', 0, 0, ?, ?, NULL)`,
        [relationshipId, owner, survivor.userId, `ccqa-${suffix}-owner-${owner}`, date, billCategory.categoryId, billCategory.name]
      );
      state.billIds.push(Number(billResult.insertId));
    }

    await proveWriteLocksActorBeforeCollection({
      user: survivor, category, collectionId: sharedInventory.collectionId, dbConfig, suffix
    });

    const soloDelete = await callApi(solo, "DELETE", "/api/auth/account", { password: solo.password });
    assert.equal(soloDelete.status, 200, JSON.stringify(soloDelete.body));
    const [soloCollectionRows] = await db.execute("SELECT COUNT(*) AS total FROM category_collections WHERE id = ?", [soloInventory.collectionId]);
    assert.equal(Number(soloCollectionRows[0].total), 0, "solo account personal collection should be removed");

    const pairDelete = await callApi(departing, "DELETE", "/api/auth/account", { password: departing.password });
    assert.equal(pairDelete.status, 200, JSON.stringify(pairDelete.body));

    const [archivedRows] = await db.execute(
      "SELECT id, status, relationship_id, archived_for_user_id, version FROM category_collections WHERE id = ?",
      [sharedInventory.collectionId]
    );
    assert.equal(archivedRows.length, 1);
    assert.equal(archivedRows[0].status, "closed");
    assert.equal(archivedRows[0].relationship_id, null);
    assert.equal(Number(archivedRows[0].archived_for_user_id), survivor.userId);

    const [savedCategoryRows] = await db.execute("SELECT id FROM item_categories WHERE id = ? AND collection_id = ?", [category.categoryId, sharedInventory.collectionId]);
    const [savedTemplateRows] = await db.execute("SELECT id FROM item_templates WHERE id = ? AND collection_id = ?", [template.templateId, sharedInventory.collectionId]);
    assert.equal(savedCategoryRows.length, 1);
    assert.equal(savedTemplateRows.length, 1);
    const survivorCategories = await getCategories(survivor, sharedInventory.collectionId);
    assert.equal(survivorCategories.canWrite, false);
    assert.ok(survivorCategories.items.some((row) => row.categoryId === category.categoryId));
    const survivorTemplates = await callApi(survivor, "GET", `/api/category-collections/${sharedInventory.collectionId}/templates?status=all`);
    assert.equal(survivorTemplates.status, 200, JSON.stringify(survivorTemplates.body));
    assert.equal(survivorTemplates.body.data.canWrite, false);
    assert.ok(survivorTemplates.body.data.items.some((row) => row.templateId === template.templateId));
    const survivorPreferences = await callApi(survivor, "GET", `/api/category-collections/${sharedInventory.collectionId}/preferences`);
    assert.equal(survivorPreferences.status, 200, JSON.stringify(survivorPreferences.body));
    assert.deepEqual(survivorPreferences.body.data.preferences.orderedTemplateIds, [template.templateId]);

    const closedWrite = await callApi(survivor, "PATCH", `/api/category-collections/${sharedInventory.collectionId}/categories/${category.categoryId}`, {
      clientMutationId: crypto.randomUUID(), expectedVersion: category.version, name: `blocked-${suffix}`
    });
    assert.equal(closedWrite.status, 409, JSON.stringify(closedWrite.body));
    assert.equal(closedWrite.body.error?.code || closedWrite.body.code, "RELATIONSHIP_CHANGED");

    const outsiderRead = await callApi(newPartner, "GET", `/api/category-collections/${sharedInventory.collectionId}/categories?status=all`);
    assert.equal(outsiderRead.status, 404, JSON.stringify(outsiderRead.body));

    const [survivorInventoryRows] = await db.execute(
      "SELECT inventory_id, user_id, relationship_id, category_id FROM inventory WHERE inventory_id = ?",
      [state.inventoryIds[0]]
    );
    assert.equal(survivorInventoryRows.length, 1);
    assert.equal(Number(survivorInventoryRows[0].user_id), survivor.userId);
    assert.equal(survivorInventoryRows[0].relationship_id, null);
    assert.equal(survivorInventoryRows[0].category_id, category.categoryId);

    const [survivorBills] = await db.execute(
      `SELECT bill_id, owner, user_id, relationship_id, category_id FROM bills
       WHERE bill_id IN (${state.billIds.map(() => "?").join(",")}) ORDER BY owner`,
      state.billIds
    );
    assert.deepEqual(survivorBills.map((row) => Number(row.owner)).sort(), [1, 2, 3]);
    assert.ok(survivorBills.every((row) => Number(row.user_id) === survivor.userId && row.relationship_id === null));
    assert.ok(survivorBills.every((row) => row.category_id === billCategory.categoryId));
    const [departingPreferences] = await db.execute("SELECT COUNT(*) AS total FROM item_template_preferences WHERE user_id = ?", [departing.userId]);
    assert.equal(Number(departingPreferences[0].total), 0);
    const [oldPersonalPreferences] = await db.execute("SELECT COUNT(*) AS total FROM item_template_preferences WHERE collection_id = ?", [departingPersonal.collectionId]);
    assert.equal(Number(oldPersonalPreferences[0].total), 0);

    const [archiveAudits] = await db.execute(
      "SELECT before_json, after_json FROM collection_audits WHERE collection_id = ? AND action = 'account_delete_archive'",
      [sharedInventory.collectionId]
    );
    assert.equal(archiveAudits.length, 1);
    const auditBefore = typeof archiveAudits[0].before_json === "string" ? JSON.parse(archiveAudits[0].before_json) : archiveAudits[0].before_json;
    const auditAfter = typeof archiveAudits[0].after_json === "string" ? JSON.parse(archiveAudits[0].after_json) : archiveAudits[0].after_json;
    assert.equal(Number(auditBefore.relationshipId), relationshipId);
    assert.deepEqual(auditBefore.memberUserIds.map(Number).sort((a, b) => a - b), [departing.userId, survivor.userId].sort((a, b) => a - b));
    assert.equal(Number(auditAfter.archivedForUserId), survivor.userId);

    const [oldVersion] = await db.execute("SELECT version FROM category_collections WHERE id = ?", [sharedInventory.collectionId]);
    const [oldAuditCount] = await db.execute("SELECT COUNT(*) AS total FROM collection_audits WHERE collection_id = ? AND action = 'account_delete_archive'", [sharedInventory.collectionId]);
    await withRollback(db, async () => archiveSharedCategoryCollectionsForAccountDeletion(db, departing.userId, [relationship]));
    const [afterDuplicate] = await db.execute("SELECT version FROM category_collections WHERE id = ?", [sharedInventory.collectionId]);
    const [afterDuplicateAuditCount] = await db.execute("SELECT COUNT(*) AS total FROM collection_audits WHERE collection_id = ? AND action = 'account_delete_archive'", [sharedInventory.collectionId]);
    assert.equal(Number(afterDuplicate[0].version), Number(oldVersion[0].version));
    assert.equal(Number(afterDuplicateAuditCount[0].total), Number(oldAuditCount[0].total));

    const nextInvite = await callApi(survivor, "POST", "/api/couple/generate-invite", {});
    assert.equal(nextInvite.status, 200, JSON.stringify(nextInvite.body));
    const nextBind = await callApi(newPartner, "POST", "/api/couple/bind", { inviteCode: nextInvite.body.data.inviteCode });
    assert.equal(nextBind.status, 200, JSON.stringify(nextBind.body));
    const [nextRelationshipRows] = await db.execute(
      "SELECT relationship_id FROM couple_relationships WHERE (user_id_1 = ? AND user_id_2 = ?) OR (user_id_1 = ? AND user_id_2 = ?)",
      [survivor.userId, newPartner.userId, newPartner.userId, survivor.userId]
    );
    assert.equal(nextRelationshipRows.length, 1);
    state.relationshipIds.push(Number(nextRelationshipRows[0].relationship_id));
    const newPartnerRead = await callApi(newPartner, "GET", `/api/category-collections/${sharedInventory.collectionId}/templates?status=all`);
    assert.equal(newPartnerRead.status, 404, JSON.stringify(newPartnerRead.body));
    const survivorStillReads = await callApi(survivor, "GET", `/api/category-collections/${sharedInventory.collectionId}/categories?status=all&limit=50`);
    assert.equal(survivorStillReads.status, 200, JSON.stringify(survivorStillReads.body));

    resultFixture = {
      users: state.users.map(({ userId }) => Number(userId)),
      archivedCollectionId: sharedInventory.collectionId,
      inventoryIds: state.inventoryIds,
      billIds: state.billIds,
      relationshipId
    };
  } finally {
    await collectExactFixtureCollections(db, state);
    for (const user of [...state.users].reverse()) {
      const [exists] = await db.execute("SELECT id FROM users WHERE id = ?", [user.userId]);
      if (exists.length === 0 || !user.accessToken) continue;
      const cleanup = await callApi(user, "DELETE", "/api/auth/account", { password: user.password });
      if (cleanup.status !== 200) {
        throw new Error(`fixture account cleanup failed for ${user.userId}: HTTP ${cleanup.status} ${JSON.stringify(cleanup.body)}`);
      }
    }
    await collectExactFixtureCollections(db, state);
    cleanedHousework = await cleanupExactFixtures(db, state);
    const residuals = {};
    const userIds = state.users.map(({ userId }) => Number(userId));
    if (userIds.length > 0) {
      const marks = userIds.map(() => "?").join(",");
      const [usersLeft] = await db.execute(`SELECT COUNT(*) AS total FROM users WHERE id IN (${marks})`, userIds);
      residuals.users = Number(usersLeft[0].total);
    }
    const collectionFilters = [];
    const collectionParams = [];
    if (state.collectionIds.size > 0) {
      const ids = [...state.collectionIds];
      collectionFilters.push(`id IN (${ids.map(() => "?").join(",")})`);
      collectionParams.push(...ids);
    }
    if (userIds.length > 0) {
      const marks = userIds.map(() => "?").join(",");
      collectionFilters.push(`owner_user_id IN (${marks})`);
      collectionFilters.push(`archived_for_user_id IN (${marks})`);
      collectionParams.push(...userIds, ...userIds);
    }
    if (state.relationshipIds.length > 0) {
      collectionFilters.push(`relationship_id IN (${state.relationshipIds.map(() => "?").join(",")})`);
      collectionParams.push(...state.relationshipIds);
    }
    if (collectionFilters.length > 0) {
      const [collectionsLeft] = await db.execute(
        `SELECT COUNT(*) AS total FROM category_collections WHERE ${collectionFilters.map((filter) => `(${filter})`).join(" OR ")}`,
        collectionParams
      );
      residuals.collections = Number(collectionsLeft[0].total);
      if (state.collectionIds.size > 0) {
        const collectionMarks = [...state.collectionIds].map(() => "?").join(",");
        for (const table of ["item_template_preferences", "collection_mutations", "collection_audits", "preset_bindings", "item_templates", "item_categories"]) {
          const [childrenLeft] = await db.execute(
            `SELECT COUNT(*) AS total FROM ${table} WHERE collection_id IN (${collectionMarks})`,
            [...state.collectionIds]
          );
          residuals[`collectionChildren.${table}`] = Number(childrenLeft[0].total);
        }
      }
    }
    if (state.relationshipIds.length > 0) {
      const marks = state.relationshipIds.map(() => "?").join(",");
      const [relationshipsLeft] = await db.execute(`SELECT COUNT(*) AS total FROM couple_relationships WHERE relationship_id IN (${marks})`, state.relationshipIds);
      residuals.relationships = Number(relationshipsLeft[0].total);
    }
    if (state.inventoryIds.length > 0) {
      const marks = state.inventoryIds.map(() => "?").join(",");
      const [inventoryLeft] = await db.execute(`SELECT COUNT(*) AS total FROM inventory WHERE inventory_id IN (${marks})`, state.inventoryIds);
      residuals.inventory = Number(inventoryLeft[0].total);
    }
    if (state.billIds.length > 0) {
      const marks = state.billIds.map(() => "?").join(",");
      const [billsLeft] = await db.execute(`SELECT COUNT(*) AS total FROM bills WHERE bill_id IN (${marks})`, state.billIds);
      residuals.bills = Number(billsLeft[0].total);
    }
    if (cleanedHousework.spaceIds.length > 0) {
      const marks = cleanedHousework.spaceIds.map(() => "?").join(",");
      const [spacesLeft] = await db.execute(`SELECT COUNT(*) AS total FROM housework_spaces WHERE space_id IN (${marks})`, cleanedHousework.spaceIds);
      residuals.spaces = Number(spacesLeft[0].total);
    } else {
      residuals.spaces = 0;
    }
    if (cleanedHousework.cycleIds.length > 0) {
      const marks = cleanedHousework.cycleIds.map(() => "?").join(",");
      const [cyclesLeft] = await db.execute(`SELECT COUNT(*) AS total FROM housework_relationship_cycles WHERE cycle_id IN (${marks})`, cleanedHousework.cycleIds);
      residuals.cycles = Number(cyclesLeft[0].total);
    } else {
      residuals.cycles = 0;
    }
    assert.ok(Object.values(residuals).every((count) => count === 0), `QA fixture leftovers: ${JSON.stringify(residuals)}`);
    console.log(JSON.stringify({
      accountDeletionRuntime: "passed",
      assertions: assertions - assertionStart,
      httpRequests: httpRequests - requestStart,
      trackedCollectionCount: state.collectionIds.size,
      trackedCollectionIds: [...state.collectionIds],
      ...resultFixture,
      fixtureResiduals: residuals
    }));
    await db.end();
  }
});
