// Phone -> desktop app bridge.
//
// tailscale serve forwards the phone here. Host/Origin are rewritten to a fixed
// loopback authority (127.0.0.1:4505) so the app's browser-trust fence accepts
// the requests and login cookies stay valid across app restarts and port
// changes. The app's real web port is taken from argv and re-discovered on
// connection failures.
//
// Login is fully automatic: when a request arrives without a dsh-auth cookie
// (typical for Android home-screen / external-app launches, which browsers
// treat as cross-site and therefore drop SameSite=Strict cookies), the proxy
// performs the token exchange with the local `dsh web` instance on the phone's
// behalf and answers a redirect carrying the cookie as SameSite=Lax.
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { execFile } = require('child_process');

// A proxy must never die: log and keep serving.
process.on('uncaughtException', (e) => console.error('uncaught:', e && e.message));
process.on('unhandledRejection', (e) => console.error('unhandled:', e && e.message));

const LISTEN_PORT = Number(process.env.DSH_PHONE_PORT) || 8124;
const CLI_PORT = Number(process.env.DSH_PHONE_CLI_PORT) || 8123; // local `dsh web` instance - performs token exchange
const AUTHORITY = '127.0.0.1:4505'; // stable identity the app trusts (loopback)
const DISCOVER = path.join(__dirname, 'find-app-port.ps1');
const CLI_LOG = process.env.DSH_PHONE_CLI_LOG || require('path').join(require('os').homedir(), '.dsh', 'logs', 'dsh-web-serve.log');
const COOKIE_ATTRS = '; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax';
let appPort = Number(process.argv[2]) || 4505;

function discoverAppPort() {
  return new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', DISCOVER], { timeout: 10000 }, async (err, out) => {
      const cands = String(out || '').split(/\s+/).map((x) => parseInt(x, 10)).filter((n) => Number.isFinite(n) && n > 0);
      if (!cands.length) return resolve(appPort);
      // The app process can host several web roots, each with its own signing
      // secret. Pick the root the minter's cookie actually unlocks: mint one
      // and probe GET / — the wrong root answers 401 even with a valid cookie.
      let pair = null;
      try { pair = await mintCookie(); } catch {}
      let guessed = !pair ? cands[0] : null;
      for (const port of cands) {
        const ok = await new Promise((res) => {
          const req = http.request({ host: '127.0.0.1', port, method: 'GET', path: '/', headers: { host: AUTHORITY, origin: 'http://' + AUTHORITY, cookie: pair || '' } }, (up) => { up.resume(); res(up.statusCode === 200); });
          req.on('error', () => res(false));
          req.setTimeout(3000, () => { req.destroy(); res(false); });
          req.end();
        });
        if (ok) { appPort = port; guessed = null; break; }
      }
      if (guessed) appPort = guessed; // no mint yet: best guess, retried later
      resolve(appPort);
    });
  });
}

function readCliToken() {
  try {
    const raw = fs.readFileSync(CLI_LOG, 'utf8').split(String.fromCharCode(0)).join('');
    const all = [...raw.matchAll(/token=([A-Za-z0-9_\-]+)/g)];
    return all.length ? all[all.length - 1][1] : null; // latest minter token wins
  } catch {
    return null;
  }
}

// Perform the launch-token exchange server-side; returns "cookieName=cookieValue".
function mintCookie() {
  return new Promise((resolve, reject) => {
    const token = readCliToken();
    if (!token) return reject(new Error('login token not found'));
    const req = http.request(
      {
        host: '127.0.0.1',
        port: CLI_PORT,
        method: 'GET',
        path: '/?token=' + encodeURIComponent(token),
        headers: { host: AUTHORITY, origin: 'http://' + AUTHORITY },
      },
      (up) => {
        const sc = up.headers['set-cookie'];
        up.resume();
        const pair = Array.isArray(sc) && sc.length ? sc[0].split(';')[0].trim() : null;
        if (up.statusCode >= 300 && up.statusCode < 400 && pair) return resolve(pair);
        reject(new Error('token exchange failed: ' + up.statusCode));
      }
    );
    req.on('error', reject);
    req.setTimeout(8000, () => req.destroy(new Error('token exchange timeout')));
    req.end();
  });
}

async function autoLogin(res) {
  try {
    const pair = await mintCookie();
    res.writeHead(303, {
      'set-cookie': pair + COOKIE_ATTRS,
      location: './',
      'cache-control': 'no-store',
      // COOP/COEP must be on EVERY hop of the redirect chain or the final
      // document is not cross-origin isolated (no SharedArrayBuffer).
      'cross-origin-opener-policy': 'same-origin',
      'cross-origin-embedder-policy': 'credentialless',
    });
    res.end();
  } catch (e) {
    waitingPage(res, '登录服务未就绪，请稍后刷新（' + e.message + '）');
  }
}

function rewriteHeaders(headers) {
  // Keep accept-encoding intact: compressed responses are the difference
  // between a 5-second and a 50-second load over a relay link.
  return { ...headers, host: AUTHORITY, origin: 'http://' + AUTHORITY };
}

function rewriteSetCookie(headers) {
  if (headers['set-cookie']) {
    headers['set-cookie'] = headers['set-cookie'].map((c) => c.split(';')[0].trim() + COOKIE_ATTRS);
  }
  return headers;
}

function waitingPage(res, note) {
  res.writeHead(503, { 'content-type': 'text/html; charset=utf-8' });
  res.end(`<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="5"><title>等待桌面 App</title>
<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#111;color:#eee;text-align:center;padding:0 24px}</style>
</head><body><div><h2>正在等待电脑上的 DSH 桌面 App…</h2>
<p>${note || 'App 未启动或还在启动中，本页每 5 秒自动重试。'}</p>
<p>若长时间没有反应，请在电脑上双击「手机远程启动.bat」。</p></div></body></html>`);
}

function forward(req, res, targetPort, retried) {
  const upstream = http.request(
    { host: '127.0.0.1', port: targetPort, method: req.method, path: req.url, headers: rewriteHeaders(req.headers) },
    (up) => {
      const headers = rewriteSetCookie({ ...up.headers });
      const type = String(headers['content-type'] || '');
      const compressible = /text\/|javascript|json|xml|svg/.test(type);
      const wantsGzip = /gzip/.test(String(req.headers['accept-encoding'] || ''));
      const enc = String(headers['content-encoding'] || '').toLowerCase();
      // Enable SharedArrayBuffer (the file-resource/WASM service needs it):
      // cross-origin isolation headers on HTML documents. credentialless keeps
      // SAB working while letting plain cross-origin images (e.g. the account
      // avatar from an external CDN, no CORP header) render — require-corp
      // silently blocked those.
      if (/text\/html/.test(type)) {
        headers['cross-origin-opener-policy'] = 'same-origin';
        headers['cross-origin-embedder-policy'] = 'credentialless';
      }
      headers['cross-origin-resource-policy'] = 'cross-origin';
      // Large uncompressed text/JSON (e.g. image payloads) is slow over the
      // relay until the client aborts. Compress it here - raw bytes only, no
      // content modification, so nothing can be corrupted.
      const bigType = /json|text|javascript|xml/.test(type);
      if (!enc && wantsGzip && bigType) {
        const chunks = [];
        up.on('data', (c) => chunks.push(c));
        up.on('end', () => {
          const raw = Buffer.concat(chunks);
          const h = { ...headers };
          delete h['content-length'];
          h['content-encoding'] = 'gzip';
          res.writeHead(up.statusCode, h);
          res.end(zlib.gzipSync(raw, { level: 6 }));
        });
        return;
      }
      res.writeHead(up.statusCode, headers);
      up.pipe(res);
      void enc;
    }
  );
  upstream.on('error', async (e) => {
    if (!retried && (e.code === 'ECONNREFUSED' || e.code === 'ECONNRESET')) {
      return forward(req, res, await discoverAppPort(), true);
    }
    waitingPage(res);
  });
  req.pipe(upstream);
}

// The rewritten /__insp path routes to the app's local resource/inspector port.
function forwardInspector(req, res) {
  const target = { host: '127.0.0.1', port: 9230, method: req.method, path: req.url.replace(/^\/__insp/, '') || '/', headers: { ...req.headers, host: '127.0.0.1:9230' } };
  const up = http.request(target, (u) => {
    res.writeHead(u.statusCode, u.headers);
    u.pipe(res);
  });
  up.on('error', () => waitingPage(res));
  req.pipe(up);
}

// ---- multi-site aggregation: stream local PC pages to the phone ----
// /__site/<relative-path> serves files from SITE_ROOT (default: the user's
// Desktop). General-purpose: any local html/file can be added as a "site".
const SITE_ROOT = process.env.DSH_SITE_ROOT || require('path').join(process.env.USERPROFILE || require('os').homedir(), 'Desktop');
const SITE_TYPES = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8', '.md': 'text/plain; charset=utf-8',
  '.pdf': 'application/pdf', '.mp4': 'video/mp4', '.webp': 'image/webp'
};
function serveSite(req, res) {
  try {
    let raw = req.url.replace(/^\/__site\//, '').split('?')[0];
    if (raw.indexOf('%') >= 0) {
      try { raw = decodeURIComponent(raw); } catch (e) {}
    } else {
      raw = Buffer.from(raw, 'latin1').toString('utf8'); // raw-byte URLs (non-ASCII names)
    }
    const pathMod = require('path');
    const full = pathMod.normalize(pathMod.join(SITE_ROOT, raw));
    const rootNorm = pathMod.normalize(SITE_ROOT);
    if (!full.toLowerCase().startsWith(rootNorm.toLowerCase())) {
      res.writeHead(403); return res.end('forbidden');
    }
    fs.stat(full, (e, st) => {
      if (e || !st.isFile()) { res.writeHead(404); return res.end('site file not found'); }
      const ext = pathMod.extname(full).toLowerCase();
      const type = SITE_TYPES[ext] || 'application/octet-stream';
      if (/\.html?$/i.test(full)) {
        // Universal local-port passthrough: pages streamed to the phone keep
        // calling http://127.0.0.1:<port> (their local APIs). Rewrite those to
        // /__local/<port>/… so the request travels back through the tunnel to
        // the PC's loopback — works for ANY monitoring page, no per-page edits.
        return fs.readFile(full, (e2, buf) => {
          if (e2 || !buf) { res.writeHead(500); return res.end('site read error'); }
          let html = buf.toString('utf8');
          if (/<head[^>]*>/i.test(html)) {
            html = html.replace(/<head[^>]*>/i, (h) => h + SITE_SHIM);
          } else {
            html = SITE_SHIM + html;
          }
          res.writeHead(200, { 'content-type': type, 'cache-control': 'no-cache' });
          res.end(html);
        });
      }
      res.writeHead(200, { 'content-type': type, 'cache-control': 'no-cache' });
      fs.createReadStream(full).pipe(res);
    });
  } catch (e) {
    res.writeHead(500); res.end('site error');
  }
}

// Injected into every streamed HTML page: rewrites 127.0.0.1/localhost URLs to
// /__local/<port>/… so fetch / XHR / WebSocket / DOM resources all reach the
// PC's loopback through the tunnel instead of the phone's own loopback.
const SITE_SHIM = `<script>(function(){
function rw(u){
  try{
    if (typeof u !== 'string') return u;
    if (u.charAt(0) === '/' && u.substring(0,2) !== '//') return u;
    var url = new URL(u, location.href);
    var h = url.hostname;
    if (h === '127.0.0.1' || h === 'localhost' || h === '[::1]' || h === '::1') {
      var port = url.port || (url.protocol === 'https:' || url.protocol === 'wss:' ? '443' : '80');
      return '/__local/' + port + url.pathname + url.search + url.hash;
    }
  }catch(e){}
  return u;
}
var of = window.fetch;
if (of) window.fetch = function(i, n){
  try{
    if (typeof i === 'string') i = rw(i);
    else if (i && i.url) i = new Request(rw(i.url), i);
  }catch(e){}
  return of.call(this, i, n);
};
var oo = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function(m, u){
  try{ arguments[1] = rw(u); }catch(e){}
  return oo.apply(this, arguments);
};
var OWS = window.WebSocket;
if (OWS) {
  window.WebSocket = function(u, p){
    var r = rw(u);
    if (r !== u) r = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + r;
    return p !== undefined ? new OWS(r, p) : new OWS(r);
  };
  window.WebSocket.prototype = OWS.prototype;
  window.WebSocket.CONNECTING = OWS.CONNECTING;
  window.WebSocket.OPEN = OWS.OPEN;
  window.WebSocket.CLOSING = OWS.CLOSING;
  window.WebSocket.CLOSED = OWS.CLOSED;
}
var OES = window.EventSource;
if (OES) window.EventSource = function(u, c){ return new OES(rw(u), c); };
function fixEl(el){
  try{
    if (!el || !el.getAttribute || el.__lfix) return;
    var as = ['src','href','action','poster','data-src'], i, a, v, r;
    for (i = 0; i < as.length; i++) {
      a = as[i]; v = el.getAttribute(a);
      if (!v) continue;
      r = rw(v);
      if (r !== v) { el.setAttribute(a, r); el.__lfix = 1; }
    }
  }catch(e){}
}
function fixAll(root){
  try{
    fixEl(root);
    var els = root.querySelectorAll ? root.querySelectorAll('[src],[href],[action],[poster]') : [];
    for (var i = 0; i < els.length; i++) fixEl(els[i]);
  }catch(e){}
}
document.addEventListener('DOMContentLoaded', function(){
  fixAll(document);
  try{
    new MutationObserver(function(ms){
      for (var i = 0; i < ms.length; i++) {
        var ad = ms[i].addedNodes;
        for (var j = 0; j < ad.length; j++) fixAll(ad[j]);
      }
    }).observe(document, { childList: true, subtree: true });
  }catch(e){}
});
})();</script>`;

// /__local/<port>/<path> -> http://127.0.0.1:<port>/<path> (loopback only)
function forwardLocal(req, res) {
  const m = req.url.match(/^\/__local\/(\d+)(\/.*)?$/);
  if (!m) { res.writeHead(400); return res.end('bad local path'); }
  const port = parseInt(m[1], 10);
  const path = m[2] || '/';
  const headers = { ...req.headers };
  delete headers.cookie; // never leak app cookies to random local services
  headers.host = '127.0.0.1:' + port;
  headers.origin = 'http://127.0.0.1:' + port;
  if (headers.referer) headers.referer = 'http://127.0.0.1:' + port + '/';
  const r = http.request({ host: '127.0.0.1', port, method: req.method, path, headers }, (up) => {
    res.writeHead(up.statusCode, up.headers);
    up.pipe(res);
  });
  r.on('error', () => { res.writeHead(502); res.end('local service unreachable'); });
  req.pipe(r);
}

function proxyUpgrade(req, socket, head, port, pathOverride, hostHeader) {
  const headers = rewriteHeaders(req.headers);
  headers['connection'] = 'Upgrade';
  headers['upgrade'] = req.headers.upgrade || 'websocket';
  if (hostHeader) headers['host'] = hostHeader;
  const r = http.request({ host: '127.0.0.1', port, method: req.method, path: pathOverride || req.url, headers });
  r.on('upgrade', (res, upstream, headBuf) => {
    const lines = ['HTTP/1.1 101 Switching Protocols'];
    for (const [k, v] of Object.entries(res.headers)) {
      lines.push(`${k}: ${Array.isArray(v) ? v.join(', ') : v}`);
    }
    socket.write(lines.join('\r\n') + '\r\n\r\n');
    if (headBuf && headBuf.length) socket.write(headBuf);
    if (head && head.length) upstream.write(head);
    // keepalive: relays love to drop idle tunnels
    try { socket.setKeepAlive(true, 15000); upstream.setKeepAlive(true, 15000); socket.setNoDelay(true); upstream.setNoDelay(true); } catch (e) {}
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  r.on('response', (res) => {
    const lines = [`HTTP/1.1 ${res.statusCode} ${res.statusMessage || ''}`];
    for (const [k, v] of Object.entries(res.headers)) {
      lines.push(`${k}: ${Array.isArray(v) ? v.join(', ') : v}`);
    }
    socket.write(lines.join('\r\n') + '\r\n\r\n');
    res.pipe(socket);
  });
  r.on('error', () => socket.destroy());
  socket.on('error', () => r.destroy());
  r.end();
}

http
  .createServer((req, res) => {
    if (req.url.startsWith('/__insp')) return forwardInspector(req, res);
    if (req.url.startsWith('/__site/')) return serveSite(req, res);
    if (req.url.startsWith('/__local/')) return forwardLocal(req, res);
    const isLogin = req.url.includes('token=');
    const hasCookie = String(req.headers.cookie || '').includes('dsh-auth-');
    if (!isLogin && !hasCookie) return autoLogin(res);
    forward(req, res, isLogin ? CLI_PORT : appPort, false);
  })
  .on('upgrade', (req, socket, head) => {
    if (req.url.startsWith('/__insp')) {
      return proxyUpgrade(req, socket, head, 9230, req.url.replace(/^\/__insp/, '') || '/', '127.0.0.1:9230');
    }
    const lm = req.url.match(/^\/__local\/(\d+)(\/.*)?$/);
    if (lm) {
      return proxyUpgrade(req, socket, head, parseInt(lm[1], 10), lm[2] || '/', '127.0.0.1:' + lm[1]);
    }
    proxyUpgrade(req, socket, head, appPort, req.url, null);
  })
  .listen(LISTEN_PORT, '127.0.0.1', () => {
    console.log(`dsh phone proxy: :${LISTEN_PORT} -> app :${appPort} (authority ${AUTHORITY}, auto-login via cli :${CLI_PORT})`);
  });

// Tailnet listener: the phone's local forwarder connects here (plain TCP inside
// WireGuard) to reach loopback-only services like the inspector channel.
try {
  const os = require('os');
  const ifs = os.networkInterfaces();
  let tailIp = null;
  for (const name of Object.keys(ifs)) {
    for (const ni of ifs[name] || []) {
      if (ni.family === 'IPv4' && ni.address.startsWith('100.')) { tailIp = ni.address; break; }
    }
    if (tailIp) break;
  }
  if (tailIp) {
    const tail = http
      .createServer((req, res) => {
        if (req.url.startsWith('/__insp')) return forwardInspector(req, res);
        if (req.url.startsWith('/__site/')) return serveSite(req, res);
        if (req.url.startsWith('/__local/')) return forwardLocal(req, res);
        forward(req, res, appPort, false);
      })
      .on('upgrade', (req, socket, head) => {
        if (req.url.startsWith('/__insp')) return proxyUpgrade(req, socket, head, 9230, req.url.replace(/^\/__insp/, '') || '/', '127.0.0.1:9230');
        const lm = req.url.match(/^\/__local\/(\d+)(\/.*)?$/);
        if (lm) return proxyUpgrade(req, socket, head, parseInt(lm[1], 10), lm[2] || '/', '127.0.0.1:' + lm[1]);
        proxyUpgrade(req, socket, head, appPort, req.url, null);
      });
    tail.on('error', (e) => console.error('tail listener:', e.message));
    tail.listen(LISTEN_PORT, tailIp, () => {
      console.log(`dsh phone proxy: tailnet listener ${tailIp}:${LISTEN_PORT}`);
    });
  }
} catch (e) {
  console.error('tail listener setup:', e.message);
}

// Pick the right web root up front (the process may host several).
discoverAppPort().then((port) => console.log('dsh phone proxy: app port ' + port));
