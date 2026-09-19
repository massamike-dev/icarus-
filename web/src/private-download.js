const MAX_APK_BYTES = 256 * 1024 * 1024;
const APK_TYPE = 'application/vnd.android.package-archive';

export class PrivateDownloadError extends Error {
  constructor(message, kind = 'error') { super(message); this.kind = kind; }
}

const fail = (message, kind) => new PrivateDownloadError(message, kind);
const checkAbort = signal => { if (signal.aborted) throw signal.reason; };

async function withDownloadRequest(options, operation) {
  const {token, signal, timeoutMs = 180000, fetchImpl = fetch} = options;
  if (typeof token !== 'string' || !token.trim()) throw fail('Sign in with your private test account, then return to this page.', 'signin');
  const controller = new AbortController();
  const cancel = () => controller.abort(fail('Download canceled. No installer was saved.', 'cancelled'));
  if (signal?.aborted) cancel();
  else signal?.addEventListener('abort', cancel, {once:true});
  const timer = setTimeout(() => controller.abort(fail('The test service took too long. Check your connection and try again.', 'timeout')), timeoutMs);
  const request = async (path, authenticated = true) => {
    checkAbort(controller.signal);
    const response = await fetchImpl(path, {
      method:'GET', signal:controller.signal, redirect:'error', cache:'no-store', credentials:'omit',
      headers:authenticated ? {authorization:`Bearer ${token}`} : {},
    });
    checkAbort(controller.signal);
    if (response.redirected) throw fail('The download service redirected unexpectedly. No installer was saved.');
    if (response.status === 401 || response.status === 403) throw fail('Your private test sign-in has expired or cannot access this installer. Sign in again.', 'signin');
    if (response.status === 404) throw fail('The private installer is not available yet. It may need to be rebuilt after the test service restarts.', 'unavailable');
    if (!response.ok) throw fail('The test service could not provide the installer. Try again shortly.');
    return response;
  };
  try { return await operation(request, controller.signal); }
  catch (error) {
    if (controller.signal.aborted) throw controller.signal.reason;
    if (error instanceof PrivateDownloadError) throw error;
    throw fail('The download could not be completed. Check your connection and try again.');
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel); }
}

async function readPrivateHealth(request) {
  const health = await (await request('/api/health', false)).json();
  if (health?.privateTest !== true) throw fail('Private test downloads are not available on this site.', 'public');
  if (!/^[a-f0-9]{40}$/.test(health.commitSha || '')) throw fail('The test service could not identify its current build. Try again after deployment.');
  return health;
}

async function readMetadata(request) {
  const health = await readPrivateHealth(request);
  const metadata = await (await request('/api/private-apk/metadata')).json();
  if (!metadata || !Number.isInteger(metadata.size) || metadata.size < 4 || metadata.size > MAX_APK_BYTES || !/^[a-f0-9]{64}$/.test(metadata.sha256 || '')) {
    throw fail('The installer details are invalid. No installer was saved.');
  }
  if (metadata.commit !== health.commitSha) throw fail('The installer does not match the current test build. Wait for the matching installer and try again.', 'stale');
  return metadata;
}

export function checkPrivateApk(options) {
  return withDownloadRequest({...options, timeoutMs:options.timeoutMs ?? 45000}, readMetadata);
}

export function downloadPrivateApk(options) {
  return withDownloadRequest(options, async (request, signal) => {
    const subtle = options.subtle || globalThis.crypto?.subtle;
    if (!subtle) throw fail('This browser cannot verify the installer. Open this HTTPS page in Chrome and try again.');
    const metadata = await readMetadata(request);
    const response = await request('/api/private-apk');
    if (response.headers.get('content-type')?.split(';')[0] !== APK_TYPE ||
        Number(response.headers.get('content-length')) !== metadata.size ||
        response.headers.get('x-icarus-apk-sha256') !== metadata.sha256) {
      await response.body?.cancel();
      throw fail('The installer response does not match its verified details. No installer was saved.');
    }
    const reader = response.body?.getReader();
    if (!reader) throw fail('This browser cannot receive the installer safely. Open this page in Chrome and try again.');
    const bytes = new Uint8Array(metadata.size);
    let received = 0, previousPercent = -1;
    try {
      while (true) {
        checkAbort(signal);
        const {done, value} = await reader.read();
        checkAbort(signal);
        if (done) break;
        if (received + value.byteLength > metadata.size) throw fail('The installer exceeded its expected size. No installer was saved.');
        bytes.set(value, received); received += value.byteLength;
        const percent = Math.floor(received / metadata.size * 100);
        if (percent !== previousPercent) { previousPercent = percent; options.onProgress?.(received, metadata.size); }
      }
    } catch (error) { await reader.cancel().catch(() => {}); throw error; }
    finally { reader.releaseLock(); }
    if (received !== metadata.size) throw fail('The download was incomplete. No installer was saved. Try again.');
    if (bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 0x03 || bytes[3] !== 0x04) throw fail('The downloaded file is not an APK archive. No installer was saved.');
    options.onVerify?.();
    const digest = await subtle.digest('SHA-256', bytes);
    checkAbort(signal);
    const checksum = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('');
    if (checksum !== metadata.sha256) throw fail('The installer integrity check failed. No installer was saved. Try again.');
    const current = await readPrivateHealth(request);
    if (current.commitSha !== metadata.commit) throw fail('The test build changed during download. Try again for the current installer.', 'stale');
    checkAbort(signal);
    return {metadata, blob:new Blob([bytes], {type:APK_TYPE}), filename:'ICARUS-Test.apk'};
  });
}
