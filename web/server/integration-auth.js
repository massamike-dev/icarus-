import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto';

// This module is server-only. Never put these client secrets, the vault key, or
// accessToken() results in API responses, browser storage, prompts, or logs.
// Provider URLs are constants: configuration cannot redirect token exchanges.
const PROVIDERS = Object.freeze({
  google: { authorize: 'https://accounts.google.com/o/oauth2/v2/auth', token: 'https://oauth2.googleapis.com/token', prefix: 'GOOGLE' },
  microsoft: { authorize: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize', token: 'https://login.microsoftonline.com/common/oauth2/v2.0/token', prefix: 'MICROSOFT' },
  dropbox: { authorize: 'https://www.dropbox.com/oauth2/authorize', token: 'https://api.dropboxapi.com/oauth2/token', prefix: 'DROPBOX' },
  github: { authorize: 'https://github.com/login/oauth/authorize', token: 'https://github.com/login/oauth/access_token', prefix: 'GITHUB' },
  slack: { authorize: 'https://slack.com/oauth/v2/authorize', token: 'https://slack.com/api/oauth.v2.access', prefix: 'SLACK' },
});
const DEFINITIONS = Object.freeze({
  gmail: { provider: 'google', scopes: ['https://www.googleapis.com/auth/gmail.readonly'] },
  'google-calendar': { provider: 'google', scopes: ['https://www.googleapis.com/auth/calendar.events.readonly'] },
  'google-drive': { provider: 'google', scopes: ['https://www.googleapis.com/auth/drive.metadata.readonly'] },
  'outlook-email': { provider: 'microsoft', scopes: ['Mail.Read', 'offline_access'] },
  'outlook-calendar': { provider: 'microsoft', scopes: ['Calendars.Read', 'offline_access'] },
  teams: { provider: 'microsoft', scopes: ['Team.ReadBasic.All', 'offline_access'] },
  sharepoint: { provider: 'microsoft', scopes: ['Sites.Read.All', 'offline_access'] },
  dropbox: { provider: 'dropbox', scopes: ['files.metadata.read'] },
  github: { provider: 'github', scopes: ['read:user'] },
  slack: { provider: 'slack', scopes: ['search:read'] },
});
const STATE_TTL = 10 * 60 * 1000;
const REFRESH_SKEW = 60 * 1000;
const fail = (message, status = 400, reauthorize = false) => Object.assign(new Error(message), { status, reauthorize });
const digest = value => createHash('sha256').update(value).digest('base64url');
const validString = (value, max = 16384) => typeof value === 'string' && value.length > 0 && value.length <= max && !/[\r\n\0]/.test(value);
const sameEntry = (record, userId, integrationId) => record.userId === userId && record.integrationId === integrationId;
const scopeList = value => typeof value === 'string' ? [...new Set(value.split(/[\s,]+/).filter(Boolean))] : null;
const requireUser = (data, userId) => { if (!data.users?.some(user => user.id === userId)) throw fail('authentication_required', 401); };
const normalizedScope = (scope, provider) => provider === 'microsoft' ? scope.replace(/^https:\/\/graph\.microsoft\.com\//i, '').toLowerCase() : scope;

function definition(userId, integrationId) {
  if (!validString(userId, 200)) throw fail('authentication_required', 401);
  if (!Object.hasOwn(DEFINITIONS, integrationId)) throw fail('integration_not_found', 404);
  return DEFINITIONS[integrationId];
}

function vaultKey(env) {
  const value = env.ICARUS_INTEGRATION_KEY;
  // Buffer.from silently accepts malformed base64; check its canonical form too.
  if (typeof value !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(value)) return null;
  const key = Buffer.from(value, 'base64');
  return key.length === 32 && key.toString('base64') === value ? key : null;
}

function configuration(env, def) {
  const provider = PROVIDERS[def.provider];
  const clientId = env[`ICARUS_${provider.prefix}_CLIENT_ID`];
  const clientSecret = env[`ICARUS_${provider.prefix}_CLIENT_SECRET`];
  // Slack PKCE is a one-way app setting and is not enabled by default. When
  // enabled Slack explicitly requires code_verifier instead of client_secret.
  const pkce = def.provider !== 'slack' || env.ICARUS_SLACK_PKCE === 'true';
  const requiresSecret = def.provider !== 'slack' || !pkce;
  const redirectUri = env.ICARUS_OAUTH_REDIRECT_URI;
  let redirect;
  try { redirect = new URL(redirectUri); } catch { return null; }
  const localDev = ['test', 'development'].includes(env.NODE_ENV) && ['localhost', '127.0.0.1', '[::1]'].includes(redirect.hostname);
  if (!validString(redirectUri, 2048) || redirect.username || redirect.password || redirect.hash || (redirect.protocol !== 'https:' && !(localDev && redirect.protocol === 'http:'))) return null;
  if (!validString(clientId, 2048) || (requiresSecret && !validString(clientSecret, 4096))) return null;
  const result = { ...provider, clientId, clientSecret: requiresSecret ? clientSecret : null, redirectUri, pkce };
  return { ...result, fingerprint: digest(JSON.stringify([def.provider, clientId, result.clientSecret, redirectUri, pkce])) };
}

function seal(key, value, associatedData) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(associatedData));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return { version: 1, iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') };
}

function unseal(key, envelope, associatedData) {
  try {
    if (!key || envelope?.version !== 1) throw new Error();
    const iv = Buffer.from(envelope.iv, 'base64'), tag = Buffer.from(envelope.tag, 'base64');
    if (iv.length !== 12 || tag.length !== 16) throw new Error();
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAAD(Buffer.from(associatedData));
    decipher.setAuthTag(tag);
    return JSON.parse(Buffer.concat([decipher.update(Buffer.from(envelope.ciphertext, 'base64')), decipher.final()]).toString('utf8'));
  } catch { throw fail('integration_reauthorization_required', 409, true); }
}
const tokenAad = (userId, id) => JSON.stringify(['icarus-integration-token-v1', userId, id]);
const stateAad = (userId, id, stateHash) => JSON.stringify(['icarus-integration-state-v1', userId, id, stateHash]);

function tokenData(payload, def, previous = null, refreshing = false) {
  // Slack's top-level access_token is normally a bot token. Only take the
  // authorized user's token; rotation returns that user token at the top level.
  const token = def.provider === 'slack' ? (payload.authed_user?.access_token ? payload.authed_user : refreshing && payload.token_type === 'user' ? payload : null) : payload;
  if (!token || !validString(token.access_token) || (token.token_type && !['bearer', ...(def.provider === 'slack' ? ['user'] : [])].includes(String(token.token_type).toLowerCase()))) throw fail('integration_invalid_token_response', 502);
  if (token.refresh_token !== undefined && !validString(token.refresh_token)) throw fail('integration_invalid_token_response', 502);
  const scopes = scopeList(token.scope) ?? previous?.scopes ?? [...def.scopes];
  if (scopes.length > 100 || scopes.some(scope => !validString(scope, 300))) throw fail('integration_invalid_token_response', 502);
  const granted = new Set(scopes.map(scope => normalizedScope(scope, def.provider)));
  if (def.scopes.filter(scope => scope !== 'offline_access').some(scope => !granted.has(normalizedScope(scope, def.provider)))) throw fail('integration_scope_not_granted', 403, true);
  let expiresAt = null;
  if (token.expires_in !== undefined) {
    const seconds = Number(token.expires_in);
    if (!Number.isFinite(seconds) || seconds <= 0 || seconds > 10 * 366 * 86400) throw fail('integration_invalid_token_response', 502);
    expiresAt = Date.now() + seconds * 1000;
  } else if (!['github', 'slack'].includes(def.provider)) {
    // Treat an omitted lifetime as expiring soon rather than using a potentially
    // stale bearer indefinitely. A refresh token can renew it on demand.
    expiresAt = Date.now() + 5 * 60 * 1000;
  }
  return {
    accessToken: token.access_token,
    // Reconnecting must not retain a refresh token from a different account.
    refreshToken: token.refresh_token || (refreshing ? previous?.refreshToken : null) || null,
    scopes, expiresAt, connectedAt: previous?.connectedAt || new Date().toISOString(), needsReauthorization: false,
  };
}

// Limit provider-controlled token responses before parsing, including chunked bodies.
async function boundedJson(response) {
  const limit = 65536;
  const announced = response.headers?.get?.('content-length');
  if (announced && Number(announced) > limit) throw new Error();
  let raw;
  if (response.body?.getReader) {
    const reader = response.body.getReader(), chunks = [];
    let size = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > limit) { await reader.cancel(); throw new Error(); }
        chunks.push(Buffer.from(value));
      }
      raw = Buffer.concat(chunks).toString('utf8');
    } finally { reader.releaseLock(); }
  } else {
    raw = await response.text();
    if (Buffer.byteLength(raw, 'utf8') > limit) throw new Error();
  }
  return JSON.parse(raw);
}

/** Server-side broker. Env: ICARUS_INTEGRATION_KEY (canonical base64, 32 bytes),
 * ICARUS_OAUTH_REDIRECT_URI, ICARUS_{GOOGLE,MICROSOFT,DROPBOX,GITHUB,SLACK}_CLIENT_ID
 * and _CLIENT_SECRET; optional ICARUS_SLACK_PKCE=true after enabling it in Slack.
 * Register the redirect as a Web/confidential-client URI, not as a SPA URI.
 * The callback UI submits code/state over authenticated POST and scrubs the URL.
 */
export function integrationAuth({ store, env = process.env, fetchImpl = fetch }) {
  const refreshes = new Map();
  const key = vaultKey(env);
  const configured = def => key && configuration(env, def);
  const requireConfig = def => { const config = configured(def); if (!config) throw fail('integration_not_configured', 503); return config; };
  const credential = async (userId, id) => {
    const data = await store.read();
    requireUser(data, userId);
    return data.integrationCredentials?.find(record => sameEntry(record, userId, id));
  };
  const decryptToken = (record, userId, id) => unseal(key, record.encrypted, tokenAad(userId, id));
  const metadata = (id, def, token = null, state = null) => {
    const isConfigured = Boolean(configured(def));
    const status = !isConfigured ? 'not_configured' : state || (!token ? 'disconnected' : token.needsReauthorization ? 'reauthorization_required' : token.expiresAt !== null && token.expiresAt <= Date.now() && !token.refreshToken ? 'expired' : 'connected');
    return { id, provider: def.provider, configured: isConfigured, connected: status === 'connected', status, scopes: token?.scopes || [...def.scopes], connectedAt: token?.connectedAt || null, expiresAt: token?.expiresAt === null || !token ? null : new Date(token.expiresAt).toISOString() };
  };
  const exchange = async (config, params) => {
    let response, payload;
    try {
      response = await fetchImpl(config.token, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
        headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
        body: new URLSearchParams({ client_id: config.clientId, ...(config.clientSecret ? { client_secret: config.clientSecret } : {}), ...params }).toString(),
      });
      // All exchanges are JSON and a redirect is never followed with credentials.
      if (response.status >= 300 && response.status < 400) throw new Error();
      payload = await boundedJson(response);
    } catch { throw fail('integration_provider_unavailable', 502); }
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw fail('integration_invalid_token_response', 502);
    if (!response.ok || payload.error || payload.ok === false) {
      const revoked = ['invalid_grant', 'invalid_refresh_token', 'invalid_auth', 'token_revoked', 'token_expired', 'account_inactive'].includes(payload.error);
      throw fail(revoked ? 'integration_reauthorization_required' : 'integration_provider_rejected', revoked ? 409 : 502, revoked);
    }
    return payload;
  };

  async function status(userId, id) {
    const def = definition(userId, id), record = await credential(userId, id);
    if (!record || !configured(def)) return metadata(id, def);
    try { return metadata(id, def, decryptToken(record, userId, id)); }
    catch { return metadata(id, def, null, 'reauthorization_required'); }
  }

  async function start(userId, id) {
    const def = definition(userId, id), config = requireConfig(def);
    const state = randomBytes(32).toString('base64url'), stateHash = digest(state);
    const verifier = config.pkce ? randomBytes(32).toString('base64url') : null;
    const expiresAt = Date.now() + STATE_TTL;
    const encrypted = seal(key, { verifier, fingerprint: config.fingerprint }, stateAad(userId, id, stateHash));
    await store.update(data => {
      requireUser(data, userId);
      const states = (data.integrationOAuthStates || []).filter(record => record.expiresAt > Date.now() && !sameEntry(record, userId, id));
      // Bound outstanding attempts per user without evicting another user's flow.
      const owned = states.filter(record => record.userId === userId);
      const evicted = new Set(owned.slice(0, Math.max(0, owned.length - 9)).map(record => record.stateHash));
      data.integrationOAuthStates = states.filter(record => !evicted.has(record.stateHash));
      data.integrationOAuthStates.push({ userId, integrationId: id, stateHash, expiresAt, encrypted });
    });
    const url = new URL(config.authorize);
    url.search = new URLSearchParams({ response_type: 'code', client_id: config.clientId, redirect_uri: config.redirectUri, state }).toString();
    url.searchParams.set(def.provider === 'slack' ? 'user_scope' : 'scope', def.scopes.join(def.provider === 'slack' ? ',' : ' '));
    if (verifier) { url.searchParams.set('code_challenge', digest(verifier)); url.searchParams.set('code_challenge_method', 'S256'); }
    if (def.provider === 'google') { url.searchParams.set('access_type', 'offline'); url.searchParams.set('prompt', 'consent'); url.searchParams.set('include_granted_scopes', 'false'); }
    if (def.provider === 'microsoft') url.searchParams.set('response_mode', 'query');
    if (def.provider === 'dropbox') url.searchParams.set('token_access_type', 'offline');
    return { url: url.toString() };
  }

  async function finish(userId, id, { code, state } = {}) {
    const def = definition(userId, id), config = requireConfig(def);
    if (!validString(code, 8192) || !/^[A-Za-z0-9_-]{43}$/.test(state || '')) throw fail('integration_invalid_callback');
    const stateHash = digest(state);
    const pending = await store.update(data => {
      requireUser(data, userId);
      const record = data.integrationOAuthStates?.find(item => sameEntry(item, userId, id) && item.stateHash === stateHash);
      if (!record || record.usedAt || record.expiresAt <= Date.now()) throw fail('integration_invalid_state');
      const pending = unseal(key, record.encrypted, stateAad(userId, id, stateHash));
      if (pending.fingerprint !== config.fingerprint) throw fail('integration_invalid_state');
      record.usedAt = Date.now(); // Persist consumption before any external request.
      return pending;
    });
    try {
      const payload = await exchange(config, { grant_type: 'authorization_code', code, redirect_uri: config.redirectUri, ...(pending.verifier ? { code_verifier: pending.verifier } : {}), ...(def.provider === 'microsoft' ? { scope: def.scopes.join(' ') } : {}) });
      const token = tokenData(payload, def);
      await store.update(data => {
        requireUser(data, userId);
        const record = data.integrationOAuthStates?.find(item => sameEntry(item, userId, id) && item.stateHash === stateHash && item.usedAt && item.expiresAt > Date.now());
        // A disconnect or a newer connect cancels an in-flight exchange too.
        if (!record) throw fail('integration_invalid_state');
        data.integrationOAuthStates = data.integrationOAuthStates.filter(item => item !== record);
        data.integrationCredentials = (data.integrationCredentials || []).filter(item => !sameEntry(item, userId, id));
        data.integrationCredentials.push({ userId, integrationId: id, grantId: randomUUID(), revision: randomUUID(), encrypted: seal(key, token, tokenAad(userId, id)) });
      });
      return metadata(id, def, token);
    } catch (error) {
      await store.update(data => { data.integrationOAuthStates = (data.integrationOAuthStates || []).filter(item => !(sameEntry(item, userId, id) && item.stateHash === stateHash)); });
      throw error;
    }
  }

  async function refresh(userId, id, def, config, expectedGrantId) {
    const record = await credential(userId, id);
    if (!record) throw fail('integration_not_connected', 409);
    if (record.grantId !== expectedGrantId) throw fail('integration_connection_changed', 409);
    const token = decryptToken(record, userId, id);
    if (token.needsReauthorization) throw fail('integration_reauthorization_required', 409, true);
    if (token.expiresAt === null || token.expiresAt > Date.now() + REFRESH_SKEW || (!token.refreshToken && token.expiresAt > Date.now())) return token.accessToken;
    if (!token.refreshToken) throw fail('integration_reauthorization_required', 409, true);
    try {
      const payload = await exchange(config, { grant_type: 'refresh_token', refresh_token: token.refreshToken, ...(def.provider === 'microsoft' ? { scope: def.scopes.join(' ') } : {}) });
      const renewed = tokenData(payload, def, token, true);
      await store.update(data => {
        requireUser(data, userId);
        const current = data.integrationCredentials?.find(item => sameEntry(item, userId, id));
        if (!current || current.revision !== record.revision) throw fail('integration_connection_changed', 409);
        current.encrypted = seal(key, renewed, tokenAad(userId, id));
        current.revision = randomUUID();
      });
      return renewed.accessToken;
    } catch (error) {
      if (error.reauthorize) await store.update(data => {
        const current = data.integrationCredentials?.find(item => sameEntry(item, userId, id));
        if (data.users?.some(user => user.id === userId) && current?.revision === record.revision) {
          // Keep only encrypted safe metadata after a revoked grant.
          current.encrypted = seal(key, { ...token, accessToken: null, refreshToken: null, needsReauthorization: true }, tokenAad(userId, id));
          current.revision = randomUUID();
        }
      });
      throw error;
    }
  }

  async function accessToken(userId, id, expectedGrantId) {
    const def = definition(userId, id), config = requireConfig(def);
    const record = await credential(userId, id);
    if (!record) throw fail('integration_not_connected', 409);
    if (expectedGrantId !== undefined && record.grantId !== expectedGrantId) throw fail('integration_connection_changed', 409);
    const lockKey = JSON.stringify([userId, id, record.grantId]);
    if (!refreshes.has(lockKey)) {
      const operation = refresh(userId, id, def, config, record.grantId).finally(() => { if (refreshes.get(lockKey) === operation) refreshes.delete(lockKey); });
      refreshes.set(lockKey, operation);
    }
    const accessToken = await refreshes.get(lockKey);
    const current = await credential(userId, id);
    if (!current || current.grantId !== record.grantId || decryptToken(current, userId, id).accessToken !== accessToken) throw fail('integration_connection_changed', 409);
    return accessToken;
  }

  async function invalidate(userId, id, grantId, expectedAccessToken) {
    definition(userId, id);
    if (!validString(grantId, 100) || !validString(expectedAccessToken)) return false;
    return store.update(data => {
      const record = data.integrationCredentials?.find(item => sameEntry(item, userId, id) && item.grantId === grantId);
      if (!record || !data.users?.some(user => user.id === userId)) return false;
      const token = decryptToken(record, userId, id);
      if (token.accessToken !== expectedAccessToken) return false;
      record.encrypted = seal(key, { ...token, accessToken: null, refreshToken: null, needsReauthorization: true }, tokenAad(userId, id));
      record.revision = randomUUID();
      return true;
    });
  }

  async function disconnect(userId, id) {
    definition(userId, id);
    await store.update(data => {
      data.integrationCredentials = (data.integrationCredentials || []).filter(record => !sameEntry(record, userId, id));
      data.integrationOAuthStates = (data.integrationOAuthStates || []).filter(record => !sameEntry(record, userId, id));
    });
    // This deletes local access only. Provider-side app revocation is separate.
    return status(userId, id);
  }

  return Object.freeze({ status, accessToken, start, finish, disconnect, invalidate });
}
