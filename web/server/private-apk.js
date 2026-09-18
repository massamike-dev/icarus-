import { createHash, createPublicKey, randomUUID, verify } from 'node:crypto';
import { mkdir, open, rename, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';

// Trust is pinned to GitHub's documented issuer and JWKS, never a token URL.
// https://docs.github.com/en/actions/reference/security/oidc
const ISSUER = 'https://token.actions.githubusercontent.com';
const JWKS_URL = `${ISSUER}/.well-known/jwks`;
const REPOSITORY = 'massamike-dev/icarus-';
const REF = 'refs/heads/work/icarus-private-test';
const SUBJECTS = new Set([
  `repo:${REPOSITORY}:ref:${REF}`,
  `repo:massamike-dev@302458048/icarus-@1304038477:ref:${REF}`,
]);
export const MAX_APK_BYTES = 256 * 1024 * 1024;
const HEADER_BYTES = 4096;
const fail = (status, message) => Object.assign(new Error(message), { status });
const denied = () => fail(401, 'invalid_upload_identity');
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const commitValue = env => /^[a-f0-9]{40}$/.test(env.RENDER_GIT_COMMIT || '') ? env.RENDER_GIT_COMMIT : null;

function tokenObject(segment) {
  if (!/^[A-Za-z0-9_-]+$/.test(segment) || segment.length > 8192) throw denied();
  const bytes = Buffer.from(segment, 'base64url');
  if (bytes.toString('base64url') !== segment) throw denied();
  try { const value = JSON.parse(bytes.toString('utf8')); if (object(value)) return value; } catch {}
  throw denied();
}

export function githubUploadVerifier({ fetchImpl = fetch, now = Date.now } = {}) {
  let keys = [], fetchedAt = 0, pending;
  async function refresh() {
    if (pending) return pending;
    pending = (async () => {
      try {
        const response = await fetchImpl(JWKS_URL, { redirect: 'error', signal: AbortSignal.timeout(10000) });
        if (!response.ok || response.redirected) throw new Error('JWKS unavailable');
        const chunks = []; let length = 0;
        for await (const chunk of response.body) {
          length += chunk.length;
          if (length > 65536) throw new Error('JWKS too large');
          chunks.push(Buffer.from(chunk));
        }
        const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (!Array.isArray(value.keys) || value.keys.length > 20) throw new Error('Invalid JWKS');
        keys = value.keys;
        fetchedAt = now();
      } catch { throw fail(503, 'oidc_verification_unavailable'); }
    })();
    try { await pending; } finally { pending = undefined; }
  }
  return async (authorization, commit) => {
    if (!commit) throw fail(503, 'private_build_commit_not_configured');
    if (typeof authorization !== 'string' || authorization.length > 18000 || !authorization.startsWith('Bearer ')) throw denied();
    const parts = authorization.slice(7).split('.');
    if (parts.length !== 3) throw denied();
    const [head, payload, signature] = parts, header = tokenObject(head), claims = tokenObject(payload);
    if (header.alg !== 'RS256' || header.typ !== 'JWT' || typeof header.kid !== 'string' || !header.kid || header.kid.length > 128 || ['jku', 'jwk', 'x5u', 'crit'].some(key => key in header)) throw denied();
    if (!/^[A-Za-z0-9_-]{100,1024}$/.test(signature)) throw denied();
    const time = Math.floor(now() / 1000);
    if (claims.iss !== ISSUER || claims.aud !== `icarus-private-apk:${REPOSITORY}` || !SUBJECTS.has(claims.sub) ||
        claims.repository !== REPOSITORY || claims.repository_id !== '1304038477' || claims.repository_owner_id !== '302458048' ||
        claims.ref !== REF || claims.workflow_ref !== `${REPOSITORY}/.github/workflows/android-private-test.yml@${REF}` ||
        !['push', 'workflow_dispatch'].includes(claims.event_name) || claims.sha !== commit ||
        !Number.isInteger(claims.iat) || !Number.isInteger(claims.exp) || claims.iat > time + 30 || claims.iat < time - 600 ||
        claims.exp <= time || claims.exp <= claims.iat || claims.exp - claims.iat > 600 ||
        (claims.nbf !== undefined && (!Number.isInteger(claims.nbf) || claims.nbf > time + 30))) throw denied();
    if (!fetchedAt || now() - fetchedAt > 300000) await refresh();
    let matches = keys.filter(key => key.kid === header.kid);
    if (!matches.length && now() - fetchedAt > 30000) { await refresh(); matches = keys.filter(key => key.kid === header.kid); }
    if (matches.length !== 1) throw denied();
    const jwk = matches[0];
    if (jwk.kty !== 'RSA' || (jwk.alg && jwk.alg !== 'RS256') || (jwk.use && jwk.use !== 'sig') || typeof jwk.n !== 'string' || jwk.n.length > 1024 || typeof jwk.e !== 'string' || jwk.e.length > 16) throw denied();
    try {
      const key = createPublicKey({ key: jwk, format: 'jwk' });
      if (key.asymmetricKeyDetails?.modulusLength < 2048 || !verify('RSA-SHA256', Buffer.from(`${head}.${payload}`), key, Buffer.from(signature, 'base64url'))) throw denied();
    } catch { throw denied(); }
    return { commit: claims.sha };
  };
}

async function writeAll(file, bytes, position) {
  let offset = 0;
  while (offset < bytes.length) {
    const result = await file.write(bytes, offset, bytes.length - offset, position + offset);
    if (!result.bytesWritten) throw new Error('Artifact write failed');
    offset += result.bytesWritten;
  }
}

export function privateApkService({ env = process.env, fetchImpl = fetch, now = Date.now, directory, maxBytes = MAX_APK_BYTES } = {}) {
  const enabled = env.ICARUS_PRIVATE_TEST === 'true';
  const commit = commitValue(env);
  // Kept outside the public web directory and separate from account data.
  const identity = createHash('sha256').update(resolve(env.ICARUS_DATA_FILE || 'unconfigured')).digest('hex').slice(0, 20);
  const folder = directory || join(tmpdir(), 'icarus-private-apk', identity);
  const artifact = join(folder, 'latest.bundle');
  const verifyIdentity = githubUploadVerifier({ fetchImpl, now });
  let uploading = false;
  const requirePrivate = () => { if (!enabled) throw fail(404, 'not_found'); };

  async function snapshot() {
    requirePrivate();
    let file;
    try {
      file = await open(artifact, 'r');
      const header = Buffer.alloc(HEADER_BYTES);
      const { bytesRead } = await file.read(header, 0, HEADER_BYTES, 0);
      if (bytesRead !== HEADER_BYTES) throw new Error('Incomplete artifact');
      const metadata = JSON.parse(header.toString('utf8'));
      const stat = await file.stat();
      if (!object(metadata) || metadata.commit !== commit || !/^[a-f0-9]{64}$/.test(metadata.sha256) || !Number.isInteger(metadata.size) || metadata.size < 4 || metadata.size > MAX_APK_BYTES || stat.size !== HEADER_BYTES + metadata.size || typeof metadata.uploadedAt !== 'string') throw new Error('Invalid artifact');
      return { file, metadata: { sha256: metadata.sha256, size: metadata.size, commit: metadata.commit, uploadedAt: metadata.uploadedAt } };
    } catch {
      await file?.close();
      throw fail(404, 'private_apk_not_available');
    }
  }

  return {
    async upload(req) {
      requirePrivate();
      await verifyIdentity(req.headers.authorization, commit);
      const checksum = req.headers['x-icarus-apk-sha256'];
      if (typeof checksum !== 'string' || !/^[a-f0-9]{64}$/.test(checksum)) throw fail(400, 'apk_checksum_required');
      if (req.headers['content-type'] !== 'application/vnd.android.package-archive' || (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity')) throw fail(415, 'apk_content_type_required');
      const suppliedLength = req.headers['content-length'];
      if (suppliedLength !== undefined && (!/^\d+$/.test(suppliedLength) || Number(suppliedLength) < 4 || Number(suppliedLength) > maxBytes)) throw fail(413, 'apk_size_limit');
      if (uploading) throw fail(409, 'apk_upload_in_progress');
      uploading = true;
      let file, temporary;
      try {
        await mkdir(folder, { recursive: true, mode: 0o700 });
        temporary = join(folder, `.upload-${randomUUID()}.tmp`);
        file = await open(temporary, 'wx', 0o600);
        const hash = createHash('sha256'); let size = 0, prefix = Buffer.alloc(0);
        // A fixed private header and APK live in one file, so atomic rename
        // publishes matching metadata and bytes together. Downloads skip it.
        await writeAll(file, Buffer.alloc(HEADER_BYTES, 32), 0);
        const source = typeof req.iterator === 'function' ? req.iterator({ destroyOnReturn: false }) : req;
        for await (const data of source) {
          const chunk = Buffer.from(data);
          if (size + chunk.length > maxBytes) throw fail(413, 'apk_size_limit');
          if (prefix.length < 4) prefix = Buffer.concat([prefix, chunk.subarray(0, 4 - prefix.length)]);
          hash.update(chunk);
          await writeAll(file, chunk, HEADER_BYTES + size);
          size += chunk.length;
        }
        if (size < 4 || !prefix.equals(Buffer.from([0x50, 0x4b, 0x03, 0x04]))) throw fail(400, 'invalid_apk_archive');
        if (suppliedLength !== undefined && Number(suppliedLength) !== size) throw fail(400, 'incomplete_apk_upload');
        const digest = hash.digest('hex');
        if (digest !== checksum) throw fail(400, 'apk_checksum_mismatch');
        const metadata = { sha256: digest, size, commit, uploadedAt: new Date(now()).toISOString() };
        const header = Buffer.alloc(HEADER_BYTES, 32);
        header.write(JSON.stringify(metadata));
        await writeAll(file, header, 0);
        await file.sync();
        await file.close(); file = undefined;
        await rename(temporary, artifact); temporary = undefined;
        return metadata;
      } finally {
        await file?.close();
        if (temporary) await unlink(temporary).catch(() => {});
        uploading = false;
      }
    },
    async metadata() {
      const { file, metadata } = await snapshot();
      await file.close();
      return metadata;
    },
    async download(res) {
      const { file, metadata } = await snapshot();
      res.writeHead(200, {
        'content-type': 'application/vnd.android.package-archive',
        'content-disposition': 'attachment; filename="ICARUS-Test.apk"',
        'content-length': metadata.size,
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
        'x-icarus-apk-sha256': metadata.sha256,
      });
      try { await pipeline(file.createReadStream({ start: HEADER_BYTES }), res); }
      catch { res.destroy(); }
      finally { await file.close().catch(() => {}); }
    },
  };
}
