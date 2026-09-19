import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash, webcrypto} from 'node:crypto';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
import {checkPrivateApk, downloadPrivateApk} from '../src/private-download.js';

const content = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 10, 20, 30, 40]);
const sha256 = createHash('sha256').update(content).digest('hex');
const commit = 'b'.repeat(40);
const metadata = {size:content.length, sha256, commit};
const health = {privateTest:true, commitSha:commit};
const json = value => new Response(JSON.stringify(value), {headers:{'content-type':'application/json'}});
function fixture({healthValue=health, metadataValue=metadata, bytes=content, headers={}, status=200, beforeBinary, finalHealth}={}) {
  const calls = []; let checks = 0;
  return {calls, fetchImpl:async (path, options) => {
    calls.push({path, options});
    if (path === '/api/health') return json(++checks > 1 && finalHealth ? finalHealth : healthValue);
    if (path === '/api/private-apk/metadata') return status === 200 ? json(metadataValue) : new Response('', {status});
    if (path === '/api/private-apk') {
      if (beforeBinary) return beforeBinary(options);
      return new Response(bytes, {headers:{'content-type':'application/vnd.android.package-archive','content-length':String(metadata.size),'x-icarus-apk-sha256':sha256,...headers}});
    }
    throw new Error('Unexpected request');
  }};
}
const download = (setup, options={}) => downloadPrivateApk({token:'tester-token', subtle:webcrypto.subtle, fetchImpl:setup.fetchImpl, ...options});

test('private download verifies complete bytes, SHA256 and exact deployment with credentials only in same-origin headers', async () => {
  const setup = fixture(), progress = [];
  const result = await download(setup, {onProgress:(received,total) => progress.push([received,total])});
  assert.equal(result.filename, 'ICARUS-Test.apk');
  assert.deepEqual(new Uint8Array(await result.blob.arrayBuffer()), content);
  assert.equal(result.metadata.sha256, sha256);
  assert.deepEqual(progress.at(-1), [content.length, content.length]);
  assert.equal(setup.calls.filter(call => call.path === '/api/health').length, 2);
  for (const call of setup.calls) {
    assert.ok(call.path.startsWith('/api/')); assert.ok(!call.path.includes('tester-token'));
    assert.equal(call.options.redirect, 'error'); assert.equal(call.options.cache, 'no-store');
    assert.equal(call.options.headers.authorization, call.path === '/api/health' ? undefined : 'Bearer tester-token');
  }
});

test('metadata check is read-only and never receives binary', async () => {
  const setup = fixture();
  assert.deepEqual(await checkPrivateApk({token:'tester-token', fetchImpl:setup.fetchImpl}), metadata);
  assert.deepEqual(setup.calls.map(call => call.path), ['/api/health','/api/private-apk/metadata']);
});

test('public mode and missing session fail closed before binary download', async () => {
  const setup = fixture({healthValue:{privateTest:false}});
  await assert.rejects(download(setup), error => error.kind === 'public');
  assert.equal(setup.calls.length, 1);
  await assert.rejects(download(setup, {token:''}), error => error.kind === 'signin');
  assert.equal(setup.calls.length, 1);
});

test('unauthorized and missing installers have recoverable distinct errors', async () => {
  for (const [status, kind] of [[401,'signin'],[403,'signin'],[404,'unavailable']]) {
    const setup = fixture({status});
    await assert.rejects(download(setup), error => error.kind === kind);
    assert.equal(setup.calls.length, 2);
  }
});

test('stale metadata, malformed checksum and oversized files are rejected before binary transfer', async () => {
  for (const metadataValue of [{...metadata,commit:'c'.repeat(40)},{...metadata,sha256:'bad'},{...metadata,size:256*1024*1024+1}]) {
    const setup = fixture({metadataValue}); await assert.rejects(download(setup)); assert.equal(setup.calls.length, 2);
  }
});

test('truncated, oversized, altered and non-APK downloads never produce a saveable blob', async () => {
  for (const [bytes, pattern] of [[content.slice(0,6),/incomplete/],[new Uint8Array([...content,1]),/exceeded/],[new Uint8Array([0x50,0x4b,3,4,1,2,3,4]),/integrity/],[new Uint8Array(8),/not an APK/]]) {
    await assert.rejects(download(fixture({bytes})), pattern);
  }
});

test('wrong download response headers and deployment changes fail closed', async () => {
  for (const headers of [{'content-length':'99'},{'content-type':'text/html'},{'x-icarus-apk-sha256':'c'.repeat(64)}]) await assert.rejects(download(fixture({headers})), /does not match/);
  await assert.rejects(download(fixture({finalHealth:{...health,commitSha:'c'.repeat(40)}})), error => error.kind === 'stale');
});

test('cancel and timeout abort in-flight fetches and do not return installer bytes', async () => {
  let binaryStarted;
  const began = new Promise(resolve => { binaryStarted = resolve; });
  const setup = fixture({beforeBinary:options => new Promise((resolve,reject) => { binaryStarted(); options.signal.addEventListener('abort', () => reject(options.signal.reason), {once:true}); })});
  const controller = new AbortController();
  const pending = download(setup, {signal:controller.signal});
  await began; controller.abort(); await assert.rejects(pending, error => error.kind === 'cancelled');
  await assert.rejects(download(setup, {timeoutMs:10}), error => error.kind === 'timeout');
});

test('cancel during checksum prevents final health and save even when crypto completes later', async () => {
  const controller = new AbortController(), setup = fixture();
  const subtle = {digest:async (...args) => { controller.abort(); return webcrypto.subtle.digest(...args); }};
  await assert.rejects(download(setup, {signal:controller.signal, subtle}), error => error.kind === 'cancelled');
  assert.equal(setup.calls.length, 3);
});

const root = fileURLToPath(new URL('../', import.meta.url));
const bundle = await build({root, logLevel:'silent', build:{write:false,minify:false}});
const code = bundle.output.find(value => value.type === 'chunk' && value.isEntry).code;
const waitFor = async condition => { for (let i=0;i<100;i++) { if (condition()) return; await new Promise(resolve => setTimeout(resolve,10)); } assert.fail('Private download UI did not reach the expected state'); };
async function mount(options={}) {
  const dom = new JSDOM('<div id="root"></div>', {url:'https://icarus.test/test-download',runScripts:'outside-only'}), w = dom.window;
  const setup = fixture(options), urls = [], revoked = [];
  w.localStorage.setItem('icarus_token','tester-token'); w.scrollTo = () => {};
  Object.defineProperty(w.crypto, 'subtle', {value:webcrypto.subtle});
  w.URL.createObjectURL = blob => { urls.push(blob); return `blob:test-${urls.length}`; };
  w.URL.revokeObjectURL = url => revoked.push(url);
  w.fetch = (path,args) => path === '/api/me' ? Promise.resolve(json({user:{name:'Tester'}})) : setup.fetchImpl(path,args);
  w.eval(code);
  await waitFor(() => w.document.querySelector('h1')?.textContent === 'Your test installer');
  const button = label => [...w.document.querySelectorAll('button')].find(value => value.textContent === label);
  return {dom,w,setup,urls,revoked,button};
}

test('UI is authenticated, waits for explicit prepare and save taps, and prevents duplicate transfers', async () => {
  const app = await mount();
  try {
    await waitFor(() => app.button('Download ICARUS Test')?.disabled === false);
    assert.equal(app.w.document.title,'Private test download | ICARUS');
    assert.equal(app.urls.length,0); assert.equal(app.setup.calls.some(call => call.path === '/api/private-apk'),false);
    app.button('Download ICARUS Test').click(); app.button('Download ICARUS Test').click();
    await waitFor(() => app.w.document.querySelector('a[download]'));
    assert.equal(app.setup.calls.filter(call => call.path === '/api/private-apk').length,1);
    assert.equal(app.urls.length,1); assert.equal(app.w.document.querySelector('a[download]').download,'ICARUS-Test.apk');
    assert.match(app.w.document.body.textContent,/Tap Save verified APK/); assert.doesNotMatch(app.w.document.body.textContent,/installed successfully/);
    const link = app.w.document.querySelector('a[download]'); link.addEventListener('click',event => event.preventDefault()); link.click();
    await waitFor(() => app.w.document.body.textContent.includes('Download requested'));
  } finally { app.dom.window.close(); }
});

test('public mode offers no installer controls and corrupted download offers retry without save link', async () => {
  const publicApp = await mount({healthValue:{privateTest:false}});
  try { await waitFor(() => publicApp.w.document.body.textContent.includes('not available on this site')); assert.equal(publicApp.button('Download ICARUS Test'),undefined); assert.equal(publicApp.urls.length,0); }
  finally { publicApp.dom.window.close(); }
  const app = await mount({bytes:content.slice(0,6)});
  try { await waitFor(() => app.button('Download ICARUS Test')?.disabled === false); app.button('Download ICARUS Test').click(); await waitFor(() => app.w.document.body.textContent.includes('incomplete')); assert.equal(app.button('Download ICARUS Test').disabled,false); assert.equal(app.w.document.querySelector('a[download]'),null); assert.equal(app.urls.length,0); }
  finally { app.dom.window.close(); }
});
