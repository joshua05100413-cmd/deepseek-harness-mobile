const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { createHash, randomBytes } = require('node:crypto');
const { createUploads } = require('./bridge/resumable-upload.cjs');

async function fixture(t, fail = false, chunkIdleMs) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'dshgo-upload-test-'));
  let received, calls = 0;
  const target = http.createServer(async (req, res) => {
    if (req.method === 'GET') { res.writeHead(req.headers.cookie === 'dsh-auth-test=owner' ? 200 : 401); res.end(); return; }
    calls++;
    const hash = createHash('sha256');
    let size = 0;
    for await (const chunk of req) { hash.update(chunk); size += chunk.length; }
    received = { hash: hash.digest('hex'), size, url: req.url };
    if (fail) { req.socket.destroy(); return; }
    res.end(JSON.stringify({ ok: true, value: { receiptId: 'receipt', file: { attachmentId: 'file', name: '大文件.bin', bytes: size } } }));
  });
  target.listen(0, '127.0.0.1');
  await once(target, 'listening');
  const uploads = createUploads({ getPort: () => target.address().port, rewriteHeaders: h => ({ ...h }), directory, chunkIdleMs });
  const proxy = http.createServer((req, res) => uploads.handle(req, res));
  proxy.listen(0, '127.0.0.1');
  await once(proxy, 'listening');
  t.after(async () => {
    uploads.close();
    proxy.closeAllConnections(); proxy.close();
    target.closeAllConnections(); target.close();
    // Only the directory created by this fixture is removed.
    await fs.rm(directory, { recursive: true, force: true });
  });
  const base = 'http://127.0.0.1:' + proxy.address().port;
  const request = async (url, method = 'GET', body, cookie = 'dsh-auth-test=owner') => {
    const response = await fetch(base + url, { method, body, headers: { cookie } });
    return { status: response.status, ...(await response.json()) };
  };
  const create = total => request('/__dsh/uploads', 'POST', JSON.stringify({ total,
    target: '/api/session/uploadFileBinary?sessionId=test-session&name=' + encodeURIComponent('大文件.bin') }));
  return { base, request, create, directory, received: () => received, calls: () => calls };
}

test('24 MiB upload resumes after partial disconnect; finalization is idempotent', { timeout: 15000 }, async t => {
  const f = await fixture(t);
  const data = randomBytes(24 * 1024 * 1024 + 17);
  const created = await f.create(data.length);
  assert.equal(created.status, 201);
  const endpoint = '/__dsh/uploads/' + created.id;
  // Break the first chunk halfway, as a network handover would.
  const partial = http.request(f.base + endpoint + '?offset=0', { method: 'PUT',
    headers: { cookie: 'dsh-auth-test=owner', 'content-length': created.chunkSize } });
  partial.on('error', () => {});
  partial.write(data.subarray(0, 8192));
  await new Promise(resolve => setTimeout(resolve, 50));
  partial.destroy();
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal((await f.request(endpoint)).offset, 0);
  assert.equal((await fs.stat(path.join(f.directory, created.id))).size, 0);
  for (let offset = 0; offset < data.length; offset += created.chunkSize) {
    const end = Math.min(offset + created.chunkSize, data.length);
    assert.equal((await f.request(endpoint + '?offset=' + offset, 'PUT', data.subarray(offset, end))).offset, end);
    if (offset === 0) {
      // Replaying a chunk whose acknowledgement was lost must not append it twice.
      assert.equal((await f.request(endpoint + '?offset=0', 'PUT', data.subarray(0, end))).status, 409);
      assert.equal((await f.request(endpoint)).offset, end);
    }
  }
  await Promise.all(Array.from({ length: 5 }, () => f.request(endpoint + '/complete', 'POST')));
  let status;
  for (let i = 0; i < 100; i++) {
    status = await f.request(endpoint);
    if (status.state === 'complete') break;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.equal(status.state, 'complete');
  assert.equal(status.result.value.file.bytes, data.length);
  assert.equal(f.received().hash, createHash('sha256').update(data).digest('hex'));
  assert.match(f.received().url, /^\/api\/session\/uploadFileBinary\?sessionId=test-session/);
  await f.request(endpoint + '/complete', 'POST');
  assert.equal(f.calls(), 1);
  assert.equal((await f.request(endpoint, 'GET', undefined, 'dsh-auth-test=someone-else')).status, 404);
});

test('uploads require a verified login, respect bounds and cannot finish incomplete data', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/__dsh/uploads', 'POST', '{}', '')).status, 401);
  assert.equal((await f.request('/__dsh/uploads', 'POST', '{}', 'dsh-auth-test=fake')).status, 401);
  assert.equal((await f.create(2 * 1024 ** 3 + 1)).status, 400);
  const { id } = await f.create(100);
  const endpoint = '/__dsh/uploads/' + id;
  assert.equal((await f.request(endpoint + '/complete', 'POST')).status, 409);
  assert.equal((await f.request(endpoint + '?offset=-1', 'PUT', 'bad')).status, 409);
  assert.equal((await f.request(endpoint, 'DELETE')).status, 200);
  assert.equal((await f.request(endpoint)).status, 404);
  assert.equal(f.calls(), 0);
});

test('lost creation replies and concurrent retries share one reservation', async t => {
  const f = await fixture(t);
  const metadata = { target: '/api/session/uploadFileBinary?sessionId=test', total: 100, requestId: '1'.repeat(48) };
  const results = await Promise.all(Array.from({ length: 12 }, () => f.request('/__dsh/uploads', 'POST', JSON.stringify(metadata))));
  assert.ok(results.every(r => r.status === 201));
  assert.equal(new Set(results.map(r => r.id)).size, 1);
  assert.equal((await fs.readdir(f.directory)).length, 1);
  const changed = await f.request('/__dsh/uploads', 'POST', JSON.stringify({ ...metadata, total: 101 }));
  assert.equal(changed.status, 409);
  // Another real upload still has room; retries did not consume all slots.
  assert.equal((await f.create(200)).status, 201);
  assert.equal(f.calls(), 0);
});

test('a silent partial TCP upload releases busy and rolls back after inactivity', {timeout: 5000}, async t => {
  const f = await fixture(t, false, 150);
  const {id} = await f.create(100);
  const endpoint = '/__dsh/uploads/' + id;
  const stuck = http.request(f.base + endpoint + '?offset=0', {method:'PUT',
    headers:{cookie:'dsh-auth-test=owner','content-length':'100'}});
  stuck.on('error',()=>{});
  const ended = new Promise(resolve => stuck.once('close', resolve));
  stuck.write('partial'); // No FIN, RST, or end(): emulate a dead relay socket.
  await ended;
  // Socket close precedes asynchronous disk rollback. The client polls busy
  // until rollback settles; require that to finish within a bounded interval.
  let status;
  for(let i=0;i<100;i++){
    status=await f.request(endpoint);
    if(!status.busy)break;
    await new Promise(resolve=>setTimeout(resolve,10));
  }
  assert.equal(status.busy, false);
  assert.equal(status.offset, 0);
  assert.equal((await fs.stat(path.join(f.directory,id))).size, 0);
  assert.equal((await f.request(endpoint+'?offset=0','PUT',Buffer.alloc(100))).offset, 100);
});

test('ambiguous desktop result is surfaced and never causes a second POST', { timeout: 5000 }, async t => {
  const f = await fixture(t, true);
  const { id } = await f.create(4);
  const endpoint = '/__dsh/uploads/' + id;
  await f.request(endpoint + '?offset=0', 'PUT', 'test');
  await f.request(endpoint + '/complete', 'POST');
  let status;
  for (let i = 0; i < 50; i++) {
    status = await f.request(endpoint);
    if (status.state === 'failed') break;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.equal(status.state, 'failed');
  assert.match(status.error, /不会自动重复提交/);
  await f.request(endpoint + '/complete', 'POST');
  assert.equal(f.calls(), 1);
});
