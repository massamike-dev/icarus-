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

export function issueToken(userId, secret, now = Date.now()) {
  const payload = encode(JSON.stringify({ sub: userId, exp: now + 30 * 864e5 }));
  const signature = createHmac('sha256', secret).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}

export function verifyToken(token, secret, now = Date.now()) {
  const [payload, signature] = String(token || '').split('.');
  if (!payload || !signature) return null;
  const expected = createHmac('sha256', secret).update(payload).digest();
  const actual = Buffer.from(signature, 'base64url');
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return null;
  try { const claims = JSON.parse(decode(payload)); return claims.exp > now ? claims.sub : null; } catch { return null; }
}
