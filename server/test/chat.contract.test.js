const test = require('node:test');
const assert = require('node:assert/strict');
const { createChatRouter } = require('../src/routes/chat');
const { cache, Keys } = require('../src/cache');

function fixture() {
  const inserts = [];
  cache.del(Keys.relationship(90001));
  const pool = { execute: async (sql, params) => {
    if (sql.includes('FROM couple_relationships')) return [[{relationship_id: 6, user_id_1: 90001, user_id_2: 90002}]];
    if (sql.startsWith('INSERT')) { inserts.push(params); return [{insertId: 101}]; }
    return [[{id: 1, is_liked: 0, is_deleted: 0}, {id: 2, is_liked: 1, is_deleted: 0}]];
  }};
  const router = createChatRouter({pool});
  async function invoke(method, path, body = {}, query = {}) {
    const handler = router.stack.find(l => l.route?.path === path && l.route.methods[method]).route.stack[0].handle;
    let response, error, status = 200;
    const res = {status(s) { status = s; return this; }, json(value) { response = value; return this; }};
    await handler({userId: 90001, body, query}, res, e => { error = e; });
    return {status: error?.status || status, response, error};
  }
  return {invoke, inserts};
}

test('a queued message for a dissolved relationship cannot be sent to the new partner', async () => {
  const f = fixture();
  const response = await f.invoke('post', '/messages', {relationshipId: 5, content: 'old conversation'});
  assert.equal(response.status, 403); assert.equal(f.inserts.length, 0);
});

test('sending requires an explicit valid relationship and accepts the active conversation', async () => {
  const f = fixture();
  assert.equal((await f.invoke('post', '/messages', {content: 'missing'})).status, 400);
  const result = await f.invoke('post', '/messages', {relationshipId: 6, content: 'current'});
  assert.equal(result.status, 201); assert.equal(f.inserts[0][0], 6);
});

test('chat list returns JSON booleans compatible with Android, including unliked rows', async () => {
  const f = fixture();
  const result = await f.invoke('get', '/messages', {}, {relationshipId: '6', limit: '-1'});
  assert.equal(result.status, 200);
  assert.deepEqual(result.response.data.messages.map(m => m.is_liked), [true, false]);
  assert.ok(result.response.data.messages.every(m => m.is_deleted === false));
});

test('chat search uses the same boolean contract as the list', async () => {
  const result = await fixture().invoke('get', '/search', {}, {relationshipId: '6', keyword: 'test'});
  assert.equal(result.status, 200);
  assert.deepEqual(result.response.data.messages.map(m => m.is_liked), [false, true]);
});
