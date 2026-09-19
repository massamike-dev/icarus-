import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHandler } from './app.js';
import { hashPassword, issueToken } from './auth.js';
import { emptyStore } from './store.js';
import { bootstrapPrivateTester, privateSessionSecret, privateTestConfig } from './private-test.js';

const password = 'fixture-only-private-password';
const secret = 'fixture-only-session-secret-at-least-32';
const env = { NODE_ENV: 'test', ICARUS_PRIVATE_TEST: 'true', ICARUS_TEST_EMAIL: 'tester@example.test', ICARUS_TEST_PASSWORD_HASH: hashPassword(password), ICARUS_SESSION_SECRET: secret };
class MemoryStore {
  constructor(data = emptyStore()) { this.data = data; }
  async read() { return structuredClone(this.data); }
  async update(fn) { const copy = structuredClone(this.data); const result = await fn(copy); this.data = copy; return result; }
}
async function fixture(fn, settings = env) {
  const store = new MemoryStore();
  await bootstrapPrivateTester(store, privateTestConfig(settings));
  const server = createServer(createHandler({ store, env: settings }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const request = async (path, { method = 'GET', data, token } = {}) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/api${path}`, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, ...(data ? { body: JSON.stringify(data) } : {}) });
    return { status: response.status, body: await response.json() };
  };
  try { await fn({ store, request }); } finally { await new Promise(resolve => server.close(resolve)); }
}
const login = (request, data = {}) => request('/auth/login', { method: 'POST', data: { email: env.ICARUS_TEST_EMAIL, password, ...data } });

test('private configuration fails closed for missing or malformed credentials and session secret', () => {
  for (const changes of [{ ICARUS_TEST_EMAIL: '' }, { ICARUS_TEST_EMAIL: 'not-an-email' }, { ICARUS_TEST_PASSWORD_HASH: '' }, { ICARUS_TEST_PASSWORD_HASH: 'plaintext-password' }, { ICARUS_PRIVATE_TEST: 'yes' }]) {
    assert.throws(() => createHandler({ store: new MemoryStore(), env: { ...env, ...changes } }));
  }
  assert.throws(() => createHandler({ store: new MemoryStore(), env: { ...env, ICARUS_SESSION_SECRET: '' } }), /SESSION_SECRET/);
});

test('private server startup refuses an implicit data path before listening', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./index.js', import.meta.url))], { env: { ...env, ICARUS_DATA_FILE: '', PORT: '0' }, encoding: 'utf8', timeout: 2000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /explicit ICARUS_DATA_FILE/);
  assert.doesNotMatch(result.stdout, /ICARUS listening/);
});

test('private bootstrap is idempotent and refuses production data, foreign testers, or removal of private mode', async () => {
  const store = new MemoryStore(), config = privateTestConfig(env);
  await bootstrapPrivateTester(store, config);
  const initial = await store.read();
  assert.equal(initial.users.length, 1);
  assert.equal(initial.users[0].email, env.ICARUS_TEST_EMAIL);
  await bootstrapPrivateTester(store, config);
  assert.deepEqual(await store.read(), initial);
  await assert.rejects(bootstrapPrivateTester(store, null), /requires ICARUS_PRIVATE_TEST/);
  await assert.rejects(bootstrapPrivateTester(store, { ...config, email: 'other@example.test' }), /does not match/);
  const production = new MemoryStore({ ...emptyStore(), users: [{ id: 'prod', email: env.ICARUS_TEST_EMAIL }] });
  await assert.rejects(bootstrapPrivateTester(production, config), /new, empty data store/);
  assert.equal((await production.read()).privateTest, undefined);
  const orphanData = new MemoryStore({ ...emptyStore(), memories: [{ content: 'production data' }] });
  await assert.rejects(bootstrapPrivateTester(orphanData, config), /new, empty data store/);
});

test('private health remains public, registration stays closed, and only provisioned credentials log in', () => fixture(async ({ store, request }) => {
  assert.deepEqual(await request('/health'), { status: 200, body: { ok: true, service: 'icarus-api', base44: false, privateTest: true } });
  assert.deepEqual(await request('/auth/config'), { status: 200, body: { privateTest: true, registrationEnabled: false } });
  const before = await store.read();
  for (const email of [env.ICARUS_TEST_EMAIL, 'outsider@example.test']) {
    assert.deepEqual(await request('/auth/register', { method: 'POST', data: { email, password } }), { status: 403, body: { error: 'registration_disabled' } });
  }
  for (const data of [{ email: 'outsider@example.test' }, { password: 'incorrect' }, { password: null }, { password: { unexpected: true } }]) {
    assert.deepEqual(await login(request, data), { status: 401, body: { error: 'invalid_credentials' } });
  }
  const result = await login(request, { email: ' TESTER@EXAMPLE.TEST ' });
  assert.equal(result.status, 200);
  assert.ok(result.body.token);
  assert.equal(result.body.user.email, env.ICARUS_TEST_EMAIL);
  assert.equal(result.body.user.passwordHash, undefined);
  assert.deepEqual(await store.read(), before);
  assert.equal((await request('/me', { token: result.body.token })).status, 200);
  assert.equal((await request('/memories', { token: result.body.token, method: 'POST', data: { content: 'private test memory' } })).status, 201);
}));

test('private APIs reject strangers, imported accounts, and public-session tokens', () => fixture(async ({ store, request }) => {
  const owner = (await store.read()).users[0];
  const signingKey = privateSessionSecret(secret, privateTestConfig(env));
  await store.update(data => data.users.push({ id: 'outsider', email: 'outsider@example.test', passwordHash: env.ICARUS_TEST_PASSWORD_HASH, privateTester: true }));
  assert.equal((await login(request, { email: 'outsider@example.test' })).status, 401);
  for (const token of [undefined, 'invalid', issueToken(owner.id, secret), issueToken('outsider', signingKey)]) {
    for (const path of ['/me', '/memories', '/conversations', '/capabilities']) assert.equal((await request(path, { token })).status, 401);
    assert.equal((await request('/commands/interpret', { token, method: 'POST', data: { command: 'flashlight on' } })).status, 401);
  }
  await store.update(data => { data.users[0].privateTester = false; });
  assert.equal((await login(request)).status, 401);
  assert.equal((await request('/me', { token: issueToken(owner.id, signingKey) })).status, 401);
}));

test('rotating private credentials revokes previous tokens while account deletion remains deleted after restart', async () => {
  const store = new MemoryStore(), oldConfig = privateTestConfig(env);
  await bootstrapPrivateTester(store, oldConfig);
  const id = (await store.read()).users[0].id;
  const newConfig = { ...oldConfig, passwordHash: hashPassword('fixture-only-replacement-password') };
  await bootstrapPrivateTester(store, newConfig);
  assert.equal((await store.read()).users[0].id, id);
  assert.equal((await store.read()).users[0].passwordHash, newConfig.passwordHash);
  assert.notEqual(privateSessionSecret(secret, oldConfig), privateSessionSecret(secret, newConfig));
  await store.update(data => { data.users = []; });
  await bootstrapPrivateTester(store, newConfig);
  assert.deepEqual((await store.read()).users, []);
});

test('public mode retains account registration and login', () => fixture(async ({ request, store }) => {
  assert.deepEqual(await request('/auth/config'), { status: 200, body: { privateTest: false, registrationEnabled: true } });
  const account = await request('/auth/register', { method: 'POST', data: { email: 'public@example.test', password } });
  assert.equal(account.status, 201);
  assert.equal((await request('/auth/login', { method: 'POST', data: { email: 'public@example.test', password } })).status, 200);
  assert.equal((await store.read()).privateTest, undefined);
}, { NODE_ENV: 'test' }));
