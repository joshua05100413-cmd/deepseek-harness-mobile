'use strict';
// Disk-backed, bounded uploads. Only this protocol is retried; commands and
// the final DSH POST are never replayed after an ambiguous upstream failure.
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { randomBytes, createHash } = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');

const CHUNK = 1024 * 1024;
const TTL = 30 * 60 * 1000;
const MAX_FILE = 2 * 1024 ** 3;
const MAX_DISK = 4 * 1024 ** 3;

function createUploads({ getPort, rewriteHeaders, directory, chunkIdleMs = 45000 }) {
  const records = new Map();
  const creations = new Map();
  let rootPromise;
  const root = () => rootPromise ||= (directory
    ? fsp.mkdir(directory, { recursive: true }).then(() => directory)
    : fsp.mkdtemp(path.join(os.tmpdir(), 'dshgo-upload-')));
  const ownerOf = req => createHash('sha256').update(String(req.headers.cookie || '')).digest('hex');
  const reply = (res, status, value) => {
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  async function authorize(req) {
    if (!/(?:^|;\s*)dsh-auth-[^=;]+=[^;]+/.test(req.headers.cookie || '')) return false;
    const port = getPort();
    if (!port) return false;
    return new Promise(resolve => {
      const headers = rewriteHeaders(req.headers);
      delete headers['content-length'];
      delete headers['transfer-encoding'];
      const up = http.get({ host: '127.0.0.1', port, path: '/', headers }, response => {
        response.resume();
        resolve(response.statusCode === 200);
      });
      up.setTimeout(10000, () => up.destroy());
      up.on('error', () => resolve(false));
    });
  }
  const timer = setInterval(() => {
    for (const [id, record] of records) {
      if (record.state !== 'creating' && !record.busy && !record.completing && Date.now() - record.touched > TTL) {
        records.delete(id);
        if (record.creationKey) creations.delete(record.creationKey);
        fsp.unlink(record.file).catch(() => {});
      }
    }
  }, 60000);
  timer.unref?.();

  async function complete(record, req) {
    try {
      const port = getPort();
      if (!port) throw new Error('desktop unavailable; upload was not submitted');
      const result = await new Promise((resolve, reject) => {
        const headers = rewriteHeaders(req.headers);
        delete headers['transfer-encoding'];
        headers['content-length'] = String(record.total);
        headers['content-type'] = 'application/octet-stream';
        headers['accept-encoding'] = 'identity';
        const up = http.request({ host: '127.0.0.1', port, method: 'POST', path: record.target, headers }, response => {
          const parts = [];
          let bytes = 0;
          response.on('data', chunk => {
            bytes += chunk.length;
            if (bytes > 1024 * 1024) response.destroy(new Error('upload receipt too large'));
            else parts.push(chunk);
          });
          response.on('error', reject);
          response.on('end', () => {
            try {
              const result = JSON.parse(Buffer.concat(parts).toString('utf8'));
              if (response.statusCode !== 200 || typeof result.ok !== 'boolean') throw new Error('invalid upload receipt');
              resolve(result);
            } catch (error) { reject(error); }
          });
        });
        up.setTimeout(120000, () => up.destroy(new Error('desktop upload timeout')));
        up.on('error', reject);
        pipeline(fs.createReadStream(record.file), up).catch(reject);
      });
      record.result = result;
      record.state = 'complete';
    } catch (error) {
      record.state = 'failed';
      record.error = '电脑未确认上传结果，请重新选择文件；不会自动重复提交。' + error.message;
    } finally {
      record.completing = false;
      record.touched = Date.now();
      await fsp.unlink(record.file).catch(() => {});
    }
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname === '/__dsh/uploads' && req.method === 'POST') {
        if (!(await authorize(req))) return reply(res, 401, { error: '请先连接工作台并登录' });
        const parts = [];
        let size = 0;
        for await (const part of req) {
          size += part.length;
          if (size > 8192) return reply(res, 413, { error: 'metadata too large' });
          parts.push(part);
        }
        const meta = JSON.parse(Buffer.concat(parts));
        const target = new URL(meta.target, 'http://localhost');
        if (target.origin !== 'http://localhost' || target.pathname !== '/api/session/uploadFileBinary'
            || !target.searchParams.get('sessionId') || !Number.isSafeInteger(meta.total)
            || meta.total < 0 || meta.total > MAX_FILE) return reply(res, 400, { error: 'invalid upload metadata (maximum 2 GiB)' });
        if (meta.requestId !== undefined && !/^[a-f0-9]{48}$/.test(meta.requestId)) return reply(res, 400, { error: 'invalid creation identity' });
        const owner = ownerOf(req);
        const targetPath = target.pathname + target.search;
        const creationKey = meta.requestId ? owner + ':' + meta.requestId : null;
        const existing = creationKey && creations.get(creationKey);
        if (existing) {
          if (existing.target !== targetPath || existing.total !== meta.total) return reply(res, 409, { error: 'creation identity reused for different file' });
          await existing.ready;
          existing.touched = Date.now();
          return reply(res, 201, { id: existing.id, offset: existing.offset, chunkSize: CHUNK });
        }
        const active = [...records.values()].filter(r => r.state === 'creating' || r.state === 'uploading' || r.completing);
        if (active.length >= 8 || active.reduce((n, r) => n + r.total, 0) + meta.total > MAX_DISK) {
          return reply(res, 429, { error: '上传暂存空间已满，请稍后重试' });
        }
        const id = randomBytes(24).toString('hex');
        const record = { id, target: targetPath, total: meta.total, creationKey,
          offset: 0, owner, touched: Date.now(), state: 'creating', busy: false };
        // Reserve quota synchronously before disk I/O. All retries of the same
        // creation share one file and cannot exhaust the eight-upload limit.
        records.set(id, record);
        if (creationKey) creations.set(creationKey, record);
        record.ready = (async () => {
          record.file = path.join(await root(), id);
          await fsp.writeFile(record.file, '', { flag: 'wx' });
          record.state = 'uploading';
        })().catch(error => {
          records.delete(id);
          if (creationKey) creations.delete(creationKey);
          throw error;
        });
        await record.ready;
        return reply(res, 201, { id, offset: 0, chunkSize: CHUNK });
      }
      const match = url.pathname.match(/^\/__dsh\/uploads\/([a-f0-9]{48})(\/complete)?$/);
      const record = match && records.get(match[1]);
      if (!record || record.owner !== ownerOf(req)) return reply(res, 404, { error: 'upload expired or not owned by this login' });
      record.touched = Date.now();
      if (req.method === 'GET' && !match[2]) {
        return reply(res, 200, { offset: record.offset, total: record.total, state: record.state, busy: !!record.busy,
          ...(record.result ? { result: record.result } : {}), ...(record.error ? { error: record.error } : {}) });
      }
      if (req.method === 'DELETE' && !match[2]) {
        if (record.completing || record.busy) return reply(res, 409, { error: 'upload busy' });
        records.delete(match[1]);
        if (record.creationKey) creations.delete(record.creationKey);
        await fsp.unlink(record.file).catch(() => {});
        return reply(res, 200, { canceled: true });
      }
      if (req.method === 'PUT' && !match[2]) {
        const offset = Number(url.searchParams.get('offset'));
        if (!url.searchParams.has('offset') || !Number.isSafeInteger(offset) || offset !== record.offset
            || record.busy || record.state !== 'uploading') return reply(res, 409, { offset: record.offset });
        record.busy = true;
        // A lost TCP connection may never deliver FIN/RST across a broken
        // relay. Bound inactivity so a partial block cannot hold busy forever.
        req.setTimeout(chunkIdleMs, () => req.destroy(new Error('upload block idle timeout')));
        let received = 0;
        try {
          const limit = new Transform({ transform(chunk, encoding, callback) {
            received += chunk.length;
            callback(received > CHUNK || offset + received > record.total ? new Error('chunk too large') : null, chunk);
          } });
          await pipeline(req, limit, fs.createWriteStream(record.file, { flags: 'r+', start: offset }));
          if (!received) throw new Error('empty chunk');
          record.offset += received;
          return reply(res, 200, { offset: record.offset });
        } catch (error) {
          await fsp.truncate(record.file, offset);
          return reply(res, 400, { error: error.message, offset });
        } finally { record.busy = false; record.touched = Date.now(); req.setTimeout(0); }
      }
      if (req.method === 'POST' && match[2]) {
        if (record.busy || record.offset !== record.total) return reply(res, 409, { offset: record.offset });
        if (record.state === 'uploading') {
          record.completing = true;
          record.state = 'completing';
          // Start exactly once. Client polls a durable result even if this
          // acknowledgement is lost; never couple submission to its socket.
          void complete(record, req);
        }
        return reply(res, 202, { state: record.state });
      }
      reply(res, 405, { error: 'method not allowed' });
    } catch (error) { reply(res, 500, { error: error.message }); }
  }
  return { handle, close() { clearInterval(timer); } };
}
module.exports = { createUploads };
