import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtemp, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { createHandler } from './app.js';
import { hashPassword } from './auth.js';
import { bootstrapPrivateTester, privateTestConfig } from './private-test.js';
import { emptyStore } from './store.js';
import { githubUploadVerifier, MAX_APK_BYTES, privateApkService } from './private-apk.js';

const key = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...key.publicKey.export({ format: 'jwk' }), kid: 'fixture-key', alg: 'RS256', use: 'sig' };
const commit = 'a'.repeat(40), second = Math.floor(Date.now() / 1000);
const baseClaims = {
  iss: 'https://token.actions.githubusercontent.com', aud: 'icarus-private-apk:massamike-dev/icarus-',
  sub: 'repo:massamike-dev/icarus-:ref:refs/heads/work/icarus-private-test', repository: 'massamike-dev/icarus-',
  repository_id: '1304038477', repository_owner_id: '302458048', ref: 'refs/heads/work/icarus-private-test',
  workflow_ref: 'massamike-dev/icarus-/.github/workflows/android-private-test.yml@refs/heads/work/icarus-private-test',
  event_name: 'push', sha: commit, iat: second, nbf: second, exp: second + 300,
};
function bearer(claims = {}, header = {}) {
  const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url');
  const input = `${encode({ alg: 'RS256', typ: 'JWT', kid: jwk.kid, ...header })}.${encode({ ...baseClaims, ...claims })}`;
  return `Bearer ${input}.${sign('RSA-SHA256', Buffer.from(input), key.privateKey).toString('base64url')}`;
}
const jwks = async () => new Response(JSON.stringify({ keys: [jwk] }));
const bytes = Buffer.from('PK\x03\x04fixture-private-apk');
const digest = value => createHash('sha256').update(value).digest('hex');
const env = { ICARUS_PRIVATE_TEST: 'true', RENDER_GIT_COMMIT: commit };
function uploadRequest(value = bytes, headers = {}) {
  const request = Readable.from([value]);
  request.headers = { authorization: bearer(), 'content-type': 'application/vnd.android.package-archive', 'x-icarus-apk-sha256': digest(value), ...headers };
  return request;
}

test('OIDC accepts only verified GitHub signatures from the exact private workflow and fixed JWKS origin', async () => {
  const calls = [];
  const verify = githubUploadVerifier({ now: () => second * 1000, fetchImpl: async (...args) => { calls.push(args); return jwks(); } });
  assert.deepEqual(await verify(bearer(), commit), { commit });
  assert.deepEqual(await verify(bearer({ event_name: 'workflow_dispatch', sub: 'repo:massamike-dev@302458048/icarus-@1304038477:ref:refs/heads/work/icarus-private-test' }), commit), { commit });
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'https://token.actions.githubusercontent.com/.well-known/jwks');
  assert.equal(calls[0][1].redirect, 'error');
  assert.equal(calls[0][1].headers, undefined);
  const token = bearer(), modified = token.slice(0, -20) + 'A'.repeat(20);
  await assert.rejects(verify(modified, commit), { status: 401 });
  for (const header of [{ alg: 'none' }, { alg: 'HS256' }, { typ: 'OTHER' }, { kid: 'unknown' }, { jku: 'https://attacker.invalid/keys' }, { x5u: 'http://localhost/secrets' }, { jwk }, { crit: ['anything'] }]) {
    await assert.rejects(verify(bearer({}, header), commit), { status: 401 });
  }
  assert.equal(calls.length, 1);
});

test('OIDC independently pins repository IDs, subject, audience, branch, workflow, event and deployed commit', async () => {
  const verify = githubUploadVerifier({ fetchImpl: jwks, now: () => second * 1000 });
  for (const changes of [
    { iss: 'https://attacker.invalid' }, { aud: 'other-audience' }, { aud: [baseClaims.aud] },
    { sub: `${baseClaims.sub}-extra` }, { sub: 'repo:massamike-dev/icarus-:pull_request' },
    { repository: 'other/icarus-' }, { repository_id: '999' }, { repository_owner_id: '999' },
    { repository_id: 1304038477 }, { ref: 'refs/heads/main' },
    { workflow_ref: baseClaims.workflow_ref.replace('android-private-test.yml', 'attacker.yml') },
    { workflow_ref: baseClaims.workflow_ref.replace('refs/heads/work/icarus-private-test', 'refs/heads/main') },
    { event_name: 'pull_request' }, { event_name: 'pull_request_target' }, { sha: 'b'.repeat(40) },
  ]) await assert.rejects(verify(bearer(changes), commit), { status: 401 }, JSON.stringify(changes));
  await assert.rejects(verify(bearer(), null), { status: 503 });
});

test('OIDC rejects expired, future, oversized and malformed tokens, and unavailable signing keys', async () => {
  const verify = githubUploadVerifier({ fetchImpl: jwks, now: () => second * 1000 });
  for (const changes of [{ exp: second }, { iat: second + 31 }, { iat: second - 601 }, { exp: second + 601 }, { exp: 'later' }, { iat: 'earlier' }, { nbf: second + 31 }, { nbf: 'tomorrow' }]) {
    await assert.rejects(verify(bearer(changes), commit), { status: 401 });
  }
  for (const token of [undefined, '', 'Bearer one.two.three', `Bearer ${'a'.repeat(18000)}`, 'Basic secret']) await assert.rejects(verify(token, commit), { status: 401 });
  const offline = githubUploadVerifier({ fetchImpl: async () => { throw new Error('network unavailable'); } });
  await assert.rejects(offline(bearer(), commit), { status: 503 });
  const redirected = githubUploadVerifier({ fetchImpl: async () => ({ ok: true, redirected: true }) });
  await assert.rejects(redirected(bearer(), commit), { status: 503 });
});

test('APK uploads verify checksums, cap streamed and declared size, and preserve the last complete artifact on failure', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'icarus-apk-unit-'));
  try {
    assert.equal(MAX_APK_BYTES, 268435456);
    const service = privateApkService({ env, fetchImpl: jwks, directory, maxBytes: 128 });
    const metadata = await service.upload(uploadRequest());
    assert.equal(metadata.sha256, digest(bytes)); assert.equal(metadata.size, bytes.length); assert.equal(metadata.commit, commit);
    assert.deepEqual(await service.metadata(), metadata);
    assert.equal((await stat(join(directory, 'latest.bundle'))).mode & 0o777, 0o600);
    await assert.rejects(service.upload(uploadRequest(bytes, { 'x-icarus-apk-sha256': 'b'.repeat(64) })), { status: 400 });
    await assert.rejects(service.upload(uploadRequest(Buffer.alloc(129))), { status: 413 });
    await assert.rejects(service.upload(uploadRequest(bytes, { 'content-length': String(MAX_APK_BYTES + 1) })), { status: 413 });
    await assert.rejects(service.upload(uploadRequest(bytes, { 'content-length': String(bytes.length + 1) })), { status: 400 });
    await assert.rejects(service.upload(uploadRequest(Buffer.from('not-an-apk'))), { status: 400 });
    await assert.rejects(service.upload(uploadRequest(bytes, { 'content-type': 'text/html' })), { status: 415 });
    const interrupted = Readable.from((async function* () { yield bytes.subarray(0, 4); throw new Error('connection lost'); })());
    interrupted.headers = uploadRequest().headers;
    await assert.rejects(service.upload(interrupted), /connection lost/);
    assert.deepEqual(await service.metadata(), metadata);
    assert.deepEqual(await readdir(directory), ['latest.bundle']);
    const stale = privateApkService({ env: { ...env, RENDER_GIT_COMMIT: 'b'.repeat(40) }, directory });
    await assert.rejects(stale.metadata(), { status: 404 });
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('an in-progress upload cannot expose partial bytes or race another upload', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'icarus-apk-race-'));
  let release;
  try {
    const service = privateApkService({ env, fetchImpl: jwks, directory });
    const previous = await service.upload(uploadRequest());
    let started;
    const pending = new Promise(resolve => { release = resolve; });
    const ready = new Promise(resolve => { started = resolve; });
    const replacement = Buffer.concat([bytes, Buffer.from('replacement')]);
    const stream = Readable.from((async function* () { yield replacement.subarray(0, 4); started(); await pending; yield replacement.subarray(4); })());
    stream.headers = uploadRequest(replacement).headers;
    const operation = service.upload(stream);
    await ready;
    assert.deepEqual(await service.metadata(), previous);
    await assert.rejects(service.upload(uploadRequest()), { status: 409 });
    release();
    const updated = await operation;
    assert.equal(updated.sha256, digest(replacement));
    assert.deepEqual(await service.metadata(), updated);
    assert.deepEqual(await readdir(directory), ['latest.bundle']);
  } finally { release?.(); await rm(directory, { recursive: true, force: true }); }
});

class MemoryStore { constructor() { this.data = emptyStore(); } async read() { return structuredClone(this.data); } async update(fn) { return fn(this.data); } }
async function serverFixture(fn, privateMode = true) {
  const directory = await mkdtemp(join(tmpdir(), 'icarus-apk-http-'));
  const settings = { NODE_ENV: 'test', ICARUS_DATA_FILE: join(directory, 'data.json'), ICARUS_SESSION_SECRET: 'fixture-private-session-secret-at-least32', ...(privateMode ? { ...env, ICARUS_TEST_EMAIL: 'tester@example.test', ICARUS_TEST_PASSWORD_HASH: hashPassword('fixture-tester-password') } : {}) };
  const store = new MemoryStore(); await bootstrapPrivateTester(store, privateTestConfig(settings));
  const server = createServer(createHandler({ store, env: settings, fetchImpl: jwks }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await fn({ base, store }); }
  finally {
    await new Promise(resolve => server.close(resolve));
    const identity = createHash('sha256').update(resolve(settings.ICARUS_DATA_FILE)).digest('hex').slice(0, 20);
    await rm(join(tmpdir(), 'icarus-private-apk', identity), { recursive: true, force: true });
    await rm(directory, { recursive: true, force: true });
  }
}

test('private HTTP delivery separates workflow upload identity from sole-tester download access', () => serverFixture(async ({ base, store }) => {
  const health = await (await fetch(`${base}/api/health`)).json(); assert.equal(health.commitSha, commit);
  const login = await (await fetch(`${base}/api/auth/login`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'tester@example.test', password: 'fixture-tester-password' }) })).json();
  const testerHeaders = { authorization: `Bearer ${login.token}` };
  for (const path of ['/api/private-apk', '/api/private-apk/metadata']) {
    assert.equal((await fetch(base + path)).status, 401);
    assert.equal((await fetch(base + path, { headers: { authorization: bearer() } })).status, 401);
    assert.equal((await fetch(base + path, { headers: testerHeaders })).status, 404);
  }
  assert.equal((await fetch(`${base}/api/private-apk`, { method: 'POST', headers: { ...uploadRequest().headers, ...testerHeaders }, body: bytes })).status, 401);
  const response = await fetch(`${base}/api/private-apk`, { method: 'POST', headers: uploadRequest().headers, body: bytes });
  assert.equal(response.status, 201); const metadata = await response.json();
  assert.deepEqual(Object.keys(metadata).sort(), ['commit', 'sha256', 'size', 'uploadedAt']);
  assert.deepEqual(await (await fetch(`${base}/api/private-apk/metadata`, { headers: testerHeaders })).json(), metadata);
  const downloaded = await fetch(`${base}/api/private-apk`, { headers: testerHeaders });
  assert.equal(downloaded.headers.get('cache-control'), 'no-store');
  assert.equal(downloaded.headers.get('content-disposition'), 'attachment; filename="ICARUS-Test.apk"');
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), bytes);
  assert.equal((await fetch(`${base}/latest.bundle`)).status, 404);
  assert.equal((await fetch(`${base}/ICARUS-Test.apk`)).status, 404);
  await store.update(data => { data.users[0].privateTester = false; });
  assert.equal((await fetch(`${base}/api/private-apk`, { headers: testerHeaders })).status, 401);
}));

test('all APK delivery routes stay unavailable in public mode', () => serverFixture(async ({ base }) => {
  assert.equal((await (await fetch(`${base}/api/health`)).json()).commitSha, undefined);
  for (const path of ['/api/private-apk', '/api/private-apk/metadata']) {
    for (const method of ['GET', 'POST']) assert.equal((await fetch(base + path, { method, headers: { authorization: bearer() } })).status, 404);
  }
}, false));
