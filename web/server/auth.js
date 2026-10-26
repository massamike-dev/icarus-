import { createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

const encode = value => Buffer.from(value).toString('base64url');
const decode = value => Buffer.from(value, 'base64url').toString();

export function hashPassword(password, salt = randomBytes(16).toString('hex')) {
  if (typeof password !== 'string' || password.length < 10) throw new Error('Password must be at least 10 characters.');
  return `${salt}:${scryptSync(password, salt, 64).toString('hex')}`;
}

export function verifyPassword(password, encoded) {
  if (typeof password !== 'string') return false;
  const [salt, expected] = String(encoded).split(':');
  if (!salt || !expected) return false;
  const actual = scryptSync(password, salt, 64);
  const wanted = Buffer.from(expected, 'hex');
  return actual.length === wanted.length && timingSafeEqual(actual, wanted);
}

export function issueToken(userId, secret, now = Date.now(), sessionVersion = 0) {
  const payload = encode(JSON.stringify({ sub: userId, exp: now + 30 * 864e5, sv: sessionVersion }));
  const signature = createHmac('sha256', secret).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}

export function verifyTokenClaims(token, secret, now = Date.now()) {
  const [payload, signature] = String(token || '').split('.');
  if (!payload || !signature) return null;
  const expected = createHmac('sha256', secret).update(payload).digest();
  const actual = Buffer.from(signature, 'base64url');
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return null;
  try { const claims = JSON.parse(decode(payload)); return typeof claims.sub === 'string' && claims.exp > now && Number.isSafeInteger(claims.sv ?? 0) && (claims.sv ?? 0) >= 0 ? claims : null; } catch { return null; }
}

export function verifyToken(token, secret, now = Date.now()) {
  return verifyTokenClaims(token, secret, now)?.sub ?? null;
}
// Missing versions in existing tokens/users are version zero, preserving existing
// sessions until the account holder changes or resets their password.
export async function authenticateToken(token, secret, store) {
  const claims = verifyTokenClaims(token, secret);
  if (!claims) return null;
  const user = (await store.read()).users.find(user => user.id === claims.sub);
  return user && (user.sessionVersion ?? 0) === (claims.sv ?? 0) ? user : null;
}
