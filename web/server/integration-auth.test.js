import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { integrationAuth } from './integration-auth.js';

class MemoryStore {
  constructor() { this.data = { users: [{ id: 'alice' }, { id: 'bob' }] }; this.queue = Promise.resolve(); }
  async read() { return structuredClone(this.data); }
  async update(fn) {
    const pending = this.queue.then(() => fn(this.data));
    this.queue = pending.catch(() => {});
    return pending;
  }
}
const KEY = Buffer.alloc(32, 7).toString('base64');
const ENV = {
  NODE_ENV: 'test', ICARUS_INTEGRATION_KEY: KEY, ICARUS_OAUTH_REDIRECT_URI: 'https://icarus.example/',
  ...Object.fromEntries(['GOOGLE', 'MICROSOFT', 'DROPBOX', 'GITHUB', 'SLACK'].flatMap(provider => [[`ICARUS_${provider}_CLIENT_ID`, `${provider}-client`], [`ICARUS_${provider}_CLIENT_SECRET`, `${provider}-secret`]])),
};
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const tokenReply = (extra = {}) => ({ access_token: 'private-access-token', refresh_token: 'private-refresh-token', expires_in: 3600, token_type: 'Bearer', ...extra });
const fixture = (fetchImpl = async () => json(tokenReply()), env = {}) => {
  const store = new MemoryStore();
  return { store, auth: integrationAuth({ store, env: { ...ENV, ...env }, fetchImpl }) };
};
async function start(auth, user = 'alice', id = 'gmail') { return new URL((await auth.start(user, id)).url); }
async function connect(auth, user = 'alice', id = 'gmail') { const url = await start(auth, user, id); return auth.finish(user, id, { code: 'one-time-auth-code', state: url.searchParams.get('state') }); }
const rejects = (fn, message, status) => assert.rejects(fn, error => error.message === message && (status === undefined || error.status === status));

const expected = [
  ['gmail', 'accounts.google.com', 'https://www.googleapis.com/auth/gmail.readonly'],
  ['google-calendar', 'accounts.google.com', 'https://www.googleapis.com/auth/calendar.events.readonly'],
  ['google-drive', 'accounts.google.com', 'https://www.googleapis.com/auth/drive.metadata.readonly'],
  ['outlook-email', 'login.microsoftonline.com', 'Mail.Read offline_access'],
  ['outlook-calendar', 'login.microsoftonline.com', 'Calendars.Read offline_access'],
  ['teams', 'login.microsoftonline.com', 'Team.ReadBasic.All offline_access'],
  ['sharepoint', 'login.microsoftonline.com', 'Sites.Read.All offline_access'],
  ['dropbox', 'www.dropbox.com', 'files.metadata.read'],
  ['github', 'github.com', 'read:user'],
  ['slack', 'slack.com', 'search:read'],
];
for (const [id, hostname, scopes] of expected) test(`${id}: starts only the fixed provider flow with read scopes`, async () => {
  let requests = 0;
  const { auth, store } = fixture(async () => { requests++; throw new Error('must not contact provider yet'); });
  const url = await start(auth, 'alice', id);
  assert.equal(url.hostname, hostname);
  assert.equal(url.protocol, 'https:');
  assert.equal(url.searchParams.get(id === 'slack' ? 'user_scope' : 'scope'), scopes);
  assert.equal(url.searchParams.get('response_type'), 'code');
  assert.equal(url.searchParams.get('redirect_uri'), ENV.ICARUS_OAUTH_REDIRECT_URI);
  assert.match(url.searchParams.get('state'), /^[A-Za-z0-9_-]{43}$/);
  assert.equal(url.searchParams.has('client_secret'), false);
  assert.equal(url.searchParams.get('code_challenge_method'), id === 'slack' ? null : 'S256');
  assert.equal(requests, 0);
  const stored = JSON.stringify(await store.read());
  assert.equal(stored.includes(url.searchParams.get('state')), false);
  assert.equal(stored.includes('verifier'), false);
  assert.equal(stored.includes('secret'), false);
});

test('missing or malformed keys/config fail closed without losing disconnect support', async () => {
  for (const env of [{ ICARUS_INTEGRATION_KEY: undefined }, { ICARUS_INTEGRATION_KEY: 'not-a-key' }, { ICARUS_INTEGRATION_KEY: Buffer.alloc(16).toString('base64') }, { ICARUS_GOOGLE_CLIENT_SECRET: '' }, { ICARUS_OAUTH_REDIRECT_URI: 'http://example.com/' }, { ICARUS_OAUTH_REDIRECT_URI: 'https://a:b@example.com/' }, { ICARUS_OAUTH_REDIRECT_URI: 'https://example.com/#callback' }, { NODE_ENV: 'production', ICARUS_OAUTH_REDIRECT_URI: 'http://localhost/' }]) {
    const { auth } = fixture(undefined, env);
    assert.equal((await auth.status('alice', 'gmail')).status, 'not_configured');
    await rejects(() => auth.start('alice', 'gmail'), 'integration_not_configured', 503);
    assert.equal((await auth.disconnect('alice', 'gmail')).connected, false);
  }
  const { auth } = fixture(undefined, { ICARUS_OAUTH_REDIRECT_URI: 'http://localhost:5173/' });
  assert.equal((await auth.status('alice', 'gmail')).configured, true);
});

test('invalid users and unknown integrations cannot create credential/state entries', async () => {
  const { auth, store } = fixture();
  await rejects(() => auth.start('', 'gmail'), 'authentication_required', 401);
  await rejects(() => auth.start('alice', '__proto__'), 'integration_not_found', 404);
  await rejects(() => auth.accessToken('alice', 'http://attacker.invalid'), 'integration_not_found', 404);
  assert.deepEqual(store.data, { users: [{ id: 'alice' }, { id: 'bob' }] });
});

test('code exchange verifies PKCE, encrypts all secrets, and returns only safe metadata', async () => {
  let request;
  const { auth, store } = fixture(async (url, options) => { request = { url, options }; return json(tokenReply({ id_token: 'discard-id-token' })); });
  const url = await start(auth);
  const result = await auth.finish('alice', 'gmail', { code: 'auth-code-secret', state: url.searchParams.get('state') });
  assert.equal(request.url, 'https://oauth2.googleapis.com/token');
  assert.equal(request.options.redirect, 'error');
  const form = new URLSearchParams(request.options.body);
  assert.equal(form.get('code'), 'auth-code-secret');
  assert.equal(form.get('redirect_uri'), ENV.ICARUS_OAUTH_REDIRECT_URI);
  assert.equal(form.get('client_secret'), ENV.ICARUS_GOOGLE_CLIENT_SECRET);
  assert.equal(form.get('grant_type'), 'authorization_code');
  assert.equal(createHash('sha256').update(form.get('code_verifier')).digest('base64url'), url.searchParams.get('code_challenge'));
  assert.equal(result.status, 'connected');
  assert.equal(result.connected, true);
  assert.equal(await auth.accessToken('alice', 'gmail'), 'private-access-token');
  assert.deepEqual(result, await auth.status('alice', 'gmail'));
  const stored = JSON.stringify(await store.read()), returned = JSON.stringify(result);
  for (const secret of ['private-access-token', 'private-refresh-token', 'discard-id-token', 'auth-code-secret', form.get('code_verifier'), ENV.ICARUS_GOOGLE_CLIENT_SECRET]) {
    assert.equal(stored.includes(secret), false);
    assert.equal(returned.includes(secret), false);
  }
  assert.equal(store.data.integrationCredentials[0].encrypted.version, 1);
  assert.deepEqual(store.data.integrationOAuthStates, []);
});

test('pending state survives a broker restart and remains bound to user/integration', async () => {
  let requests = 0;
  const fetchImpl = async () => { requests++; return json(tokenReply()); };
  const { auth, store } = fixture(fetchImpl);
  const url = await start(auth), state = url.searchParams.get('state');
  const restarted = integrationAuth({ store, env: ENV, fetchImpl });
  await rejects(() => restarted.finish('bob', 'gmail', { code: 'valid-code', state }), 'integration_invalid_state');
  await rejects(() => restarted.finish('alice', 'google-drive', { code: 'valid-code', state }), 'integration_invalid_state');
  assert.equal(requests, 0);
  await restarted.finish('alice', 'gmail', { code: 'valid-code', state });
  await rejects(() => restarted.finish('alice', 'gmail', { code: 'valid-code', state }), 'integration_invalid_state');
  assert.equal(requests, 1);
});

test('state expires at 10 minutes and is single use even after provider failure', async t => {
  let time = Date.now(), requests = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth } = fixture(async () => { requests++; throw new Error('provider detailed secret'); });
  let url = await start(auth), state = url.searchParams.get('state');
  time += 10 * 60 * 1000;
  await rejects(() => auth.finish('alice', 'gmail', { code: 'valid-code', state }), 'integration_invalid_state');
  assert.equal(requests, 0);
  url = await start(auth); state = url.searchParams.get('state');
  await rejects(() => auth.finish('alice', 'gmail', { code: 'valid-code', state }), 'integration_provider_unavailable', 502);
  await rejects(() => auth.finish('alice', 'gmail', { code: 'valid-code', state }), 'integration_invalid_state');
  assert.equal(requests, 1);
});

test('new attempts cancel older callbacks and changed configuration invalidates pending state', async () => {
  const { auth, store } = fixture();
  const old = await start(auth), newer = await start(auth);
  await rejects(() => auth.finish('alice', 'gmail', { code: 'valid-code', state: old.searchParams.get('state') }), 'integration_invalid_state');
  const changed = integrationAuth({ store, env: { ...ENV, ICARUS_GOOGLE_CLIENT_ID: 'new-client' }, fetchImpl: async () => { throw new Error('not called'); } });
  await rejects(() => changed.finish('alice', 'gmail', { code: 'valid-code', state: newer.searchParams.get('state') }), 'integration_invalid_state');
});

test('concurrent callback replay exchanges one code only', async () => {
  let calls = 0;
  const { auth } = fixture(async () => { calls++; return json(tokenReply()); });
  const state = (await start(auth)).searchParams.get('state');
  const outcomes = await Promise.allSettled([auth.finish('alice', 'gmail', { code: 'valid-code', state }), auth.finish('alice', 'gmail', { code: 'valid-code', state })]);
  assert.equal(outcomes.filter(item => item.status === 'fulfilled').length, 1);
  assert.equal(calls, 1);
});

test('credentials isolate users and integrations; ciphertext swapping and tampering fail closed', async () => {
  const { auth, store } = fixture();
  await connect(auth);
  assert.equal((await auth.status('bob', 'gmail')).status, 'disconnected');
  assert.equal((await auth.status('alice', 'google-drive')).status, 'disconnected');
  await rejects(() => auth.accessToken('bob', 'gmail'), 'integration_not_connected', 409);
  const stolen = structuredClone(store.data.integrationCredentials[0]);
  store.data.integrationCredentials.push({ ...stolen, userId: 'bob' }, { ...stolen, integrationId: 'google-drive' });
  await rejects(() => auth.accessToken('bob', 'gmail'), 'integration_reauthorization_required');
  await rejects(() => auth.accessToken('alice', 'google-drive'), 'integration_reauthorization_required');
  const bytes = Buffer.from(store.data.integrationCredentials[0].encrypted.ciphertext, 'base64'); bytes[0] ^= 1;
  store.data.integrationCredentials[0].encrypted.ciphertext = bytes.toString('base64');
  assert.equal((await auth.status('alice', 'gmail')).status, 'reauthorization_required');
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_reauthorization_required');
});

test('expiration refreshes once for concurrent calls, persists rotated refresh tokens', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const forms = [];
  const { auth } = fixture(async (_url, options) => {
    calls++; forms.push(new URLSearchParams(options.body));
    return json(tokenReply(calls === 1 ? {} : { access_token: `new-access-${calls}`, refresh_token: `new-refresh-${calls}` }));
  });
  await connect(auth); time += 3600 * 1000;
  assert.equal((await auth.status('alice', 'gmail')).connected, true);
  assert.deepEqual(await Promise.all([auth.accessToken('alice', 'gmail'), auth.accessToken('alice', 'gmail')]), ['new-access-2', 'new-access-2']);
  assert.equal(calls, 2);
  assert.equal(forms[1].get('refresh_token'), 'private-refresh-token');
  assert.equal(forms[1].has('code_verifier'), false);
  time += 3600 * 1000;
  assert.equal(await auth.accessToken('alice', 'gmail'), 'new-access-3');
  assert.equal(forms[2].get('refresh_token'), 'new-refresh-2');
});

test('refresh-token omission preserves old refresh token only during refresh, never reconnect', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const forms = [];
  const { auth } = fixture(async (_url, options) => { calls++; forms.push(new URLSearchParams(options.body)); return json(calls === 1 ? tokenReply() : { access_token: 'other-access', expires_in: 3600 }); });
  await connect(auth); time += 3600 * 1000;
  await auth.accessToken('alice', 'gmail'); time += 3600 * 1000;
  await auth.accessToken('alice', 'gmail');
  assert.equal(forms[2].get('refresh_token'), 'private-refresh-token');
  await connect(auth); time += 3600 * 1000;
  assert.equal((await auth.status('alice', 'gmail')).status, 'expired');
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_reauthorization_required');
});

test('revoked grants require reconnection and raw provider errors never escape', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth } = fixture(async () => ++calls === 1 ? json(tokenReply()) : json({ error: 'invalid_grant', error_description: 'private-refresh-token client-secret' }, 400));
  await connect(auth); time += 3600 * 1000;
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_reauthorization_required', 409);
  assert.equal((await auth.status('alice', 'gmail')).status, 'reauthorization_required');
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_reauthorization_required', 409);
  assert.equal(calls, 2);
});

test('transient refresh failures preserve credential so later requests can recover', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth } = fixture(async () => ++calls === 2 ? json({ error: 'temporarily_unavailable', detail: 'private' }, 503) : json(tokenReply()));
  await connect(auth); time += 3600 * 1000;
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_provider_rejected', 502);
  assert.equal((await auth.status('alice', 'gmail')).connected, true);
  assert.equal(await auth.accessToken('alice', 'gmail'), 'private-access-token');
});

test('disconnect removes owned pending states and ciphertext, without revoking at provider', async () => {
  let calls = 0;
  const { auth, store } = fixture(async () => { calls++; return json(tokenReply()); });
  await connect(auth); await connect(auth, 'bob'); await start(auth);
  assert.equal((await auth.disconnect('alice', 'gmail')).status, 'disconnected');
  assert.equal(calls, 2);
  assert.equal(store.data.integrationCredentials.length, 1);
  assert.equal(store.data.integrationCredentials[0].userId, 'bob');
  assert.equal(store.data.integrationOAuthStates.length, 0);
});

test('disconnect cancels an in-flight authorization exchange', async () => {
  let release, entered;
  const started = new Promise(resolve => { entered = resolve; });
  const { auth, store } = fixture(async () => { entered(); await new Promise(resolve => { release = resolve; }); return json(tokenReply()); });
  const state = (await start(auth)).searchParams.get('state');
  const pending = auth.finish('alice', 'gmail', { code: 'valid-code', state });
  await started; await auth.disconnect('alice', 'gmail'); release();
  await rejects(() => pending, 'integration_invalid_state');
  assert.equal(store.data.integrationCredentials.length, 0);
});

test('disconnect cannot be undone by a late refresh', async t => {
  let time = Date.now(), calls = 0, release, entered;
  t.mock.method(Date, 'now', () => time);
  const started = new Promise(resolve => { entered = resolve; });
  const { auth, store } = fixture(async () => {
    if (++calls === 2) { entered(); await new Promise(resolve => { release = resolve; }); }
    return json(tokenReply());
  });
  await connect(auth); time += 3600 * 1000;
  const pending = auth.accessToken('alice', 'gmail');
  await started; await auth.disconnect('alice', 'gmail'); release();
  await rejects(() => pending, 'integration_connection_changed');
  assert.equal(store.data.integrationCredentials.length, 0);
});

test('Slack stores the user token, uses optional PKCE and supports rotating user tokens', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const forms = [];
  const { auth, store } = fixture(async (_url, options) => {
    forms.push(new URLSearchParams(options.body));
    return json(++calls === 1 ? { ok: true, access_token: 'bot-must-be-ignored', authed_user: { ...tokenReply(), token_type: 'user', scope: 'search:read' } } : { ok: true, ...tokenReply({ access_token: 'rotated-slack-user' }), token_type: 'user', scope: 'search:read' });
  }, { ICARUS_SLACK_PKCE: 'true', ICARUS_SLACK_CLIENT_SECRET: undefined });
  const url = await start(auth, 'alice', 'slack');
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  await auth.finish('alice', 'slack', { code: 'valid-code', state: url.searchParams.get('state') });
  assert.equal(await auth.accessToken('alice', 'slack'), 'private-access-token');
  assert.equal(JSON.stringify(store.data).includes('bot-must-be-ignored'), false);
  assert.equal(forms[0].has('client_secret'), false);
  assert.ok(forms[0].get('code_verifier'));
  time += 3600 * 1000;
  assert.equal(await auth.accessToken('alice', 'slack'), 'rotated-slack-user');
  assert.equal(forms[1].has('client_secret'), false);
});

test('Slack refuses bot-only tokens instead of granting another identity access', async () => {
  const { auth } = fixture(async () => json({ ok: true, access_token: 'bot-token', scope: 'search:read', token_type: 'bot' }));
  await rejects(() => connect(auth, 'alice', 'slack'), 'integration_invalid_token_response', 502);
});

test('Microsoft refresh scopes remain integration-specific and accept Graph-qualified scope responses', async t => {
  let time = Date.now(); t.mock.method(Date, 'now', () => time);
  const forms = [];
  const { auth } = fixture(async (url, options) => {
    assert.equal(url, 'https://login.microsoftonline.com/common/oauth2/v2.0/token');
    forms.push(new URLSearchParams(options.body));
    return json(tokenReply({ scope: 'https://graph.microsoft.com/Mail.Read' }));
  });
  await connect(auth, 'alice', 'outlook-email'); time += 3600 * 1000;
  await auth.accessToken('alice', 'outlook-email');
  assert.deepEqual(forms.map(form => form.get('scope')), ['Mail.Read offline_access', 'Mail.Read offline_access']);
});

test('insufficient grants, non-bearer tokens, malformed lifetimes and redirected exchanges fail closed', async () => {
  for (const payload of [tokenReply({ scope: 'unrelated.scope' }), tokenReply({ token_type: 'mac' }), tokenReply({ expires_in: 'not-a-number' }), tokenReply({ expires_in: 0 }), { refresh_token: 'only-refresh' }]) {
    const { auth, store } = fixture(async () => json(payload));
    await assert.rejects(() => connect(auth));
    assert.equal((store.data.integrationCredentials || []).length, 0);
  }
  const { auth } = fixture(async () => new Response(null, { status: 307, headers: { location: 'https://attacker.invalid/' } }));
  await rejects(() => connect(auth), 'integration_provider_unavailable');
});

test('configurable token/auth endpoints are ignored, never used as token destinations', async () => {
  let destination;
  const { auth } = fixture(async url => { destination = url; return json(tokenReply()); }, { ICARUS_GOOGLE_TOKEN_URL: 'https://attacker.invalid', ICARUS_GOOGLE_AUTH_URL: 'https://attacker.invalid' });
  assert.equal((await start(auth)).hostname, 'accounts.google.com');
  await connect(auth);
  assert.equal(destination, 'https://oauth2.googleapis.com/token');
});

test('deleted/nonexistent accounts cannot start, finish or refresh grants', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth, store } = fixture(async () => { calls++; return json(tokenReply()); });
  await rejects(() => auth.start('missing-user', 'gmail'), 'authentication_required', 401);
  const state = (await start(auth)).searchParams.get('state');
  store.data.users = store.data.users.filter(user => user.id !== 'alice');
  await rejects(() => auth.finish('alice', 'gmail', { code: 'valid-code', state }), 'authentication_required', 401);
  assert.equal(calls, 0);
  store.data.users.push({ id: 'alice' });
  await connect(auth); time += 3600 * 1000;
  store.data.users = store.data.users.filter(user => user.id !== 'alice');
  await rejects(() => auth.accessToken('alice', 'gmail'), 'authentication_required', 401);
  assert.equal(calls, 1);
});

test('account deletion during token exchange prevents a credential write', async () => {
  let entered, release;
  const started = new Promise(resolve => { entered = resolve; });
  const { auth, store } = fixture(async () => { entered(); await new Promise(resolve => { release = resolve; }); return json(tokenReply()); });
  const state = (await start(auth)).searchParams.get('state');
  const pending = auth.finish('alice', 'gmail', { code: 'valid-code', state });
  await started;
  // Check the user guard even if account cleanup left an outstanding state.
  store.data.users = store.data.users.filter(user => user.id !== 'alice');
  release();
  await rejects(() => pending, 'authentication_required', 401);
  assert.equal((store.data.integrationCredentials || []).length, 0);
});

test('grant identity survives refresh but a replaced consent cannot satisfy old work', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth, store } = fixture(async () => json(tokenReply({ access_token: `token-${++calls}` })));
  await connect(auth);
  const initial = structuredClone(store.data.integrationCredentials[0]);
  time += 3600 * 1000;
  assert.equal(await auth.accessToken('alice', 'gmail', initial.grantId), 'token-2');
  assert.equal(store.data.integrationCredentials[0].grantId, initial.grantId);
  assert.notEqual(store.data.integrationCredentials[0].revision, initial.revision);
  await connect(auth);
  assert.notEqual(store.data.integrationCredentials[0].grantId, initial.grantId);
  await rejects(() => auth.accessToken('alice', 'gmail', initial.grantId), 'integration_connection_changed', 409);
  assert.equal(await auth.invalidate('alice', 'gmail', initial.grantId, 'token-1'), false);
  assert.equal((await auth.status('alice', 'gmail')).connected, true);
});

test('an old API 401 cannot invalidate a token refreshed since that request', async t => {
  let time = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => time);
  const { auth, store } = fixture(async () => json(tokenReply({ access_token: `token-${++calls}` })));
  await connect(auth);
  const { grantId } = store.data.integrationCredentials[0];
  time += 3600 * 1000;
  assert.equal(await auth.accessToken('alice', 'gmail', grantId), 'token-2');
  assert.equal(await auth.invalidate('alice', 'gmail', grantId, 'token-1'), false);
  assert.equal((await auth.status('alice', 'gmail')).connected, true);
  assert.equal(await auth.invalidate('alice', 'gmail', grantId, 'token-2'), true);
  assert.equal((await auth.status('alice', 'gmail')).status, 'reauthorization_required');
  await rejects(() => auth.accessToken('alice', 'gmail'), 'integration_reauthorization_required');
});

test('oversized provider bodies are bounded before JSON parsing', async () => {
  const oversized = JSON.stringify(tokenReply({ padding: 'x'.repeat(66000) }));
  for (const response of [() => new Response(oversized), () => new Response('{}', { headers: { 'content-length': '66000' } })]) {
    const { auth, store } = fixture(async () => response());
    await rejects(() => connect(auth), 'integration_provider_unavailable');
    assert.equal((store.data.integrationCredentials || []).length, 0);
  }
});
