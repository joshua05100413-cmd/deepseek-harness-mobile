// node --test mobile-ui.test.cjs (Edge on Windows, or set DSH_TEST_BROWSER).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const http = require('node:http');
const {once} = require('node:events');

async function connect(url) {
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let id = 0;
  const pending = new Map();
  ws.onmessage = ({data}) => {
    const message = JSON.parse(data);
    const callback = pending.get(message.id);
    if (!callback) return;
    pending.delete(message.id);
    if (message.error) callback.reject(new Error(message.error.message));
    else callback.resolve(message.result);
  };
  return {
    ws,
    call(method, params = {}) {
      return new Promise((resolve, reject) => {
        const n = ++id;
        pending.set(n, {resolve, reject});
        ws.send(JSON.stringify({id: n, method, params}));
      });
    }
  };
}

test('mobile drawer layout, interaction and injection regressions', {timeout: 30000}, async t => {
  const executable = process.env.DSH_TEST_BROWSER
    || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  if (!fs.existsSync(executable)) { t.skip('Set DSH_TEST_BROWSER to a Chromium browser'); return; }
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-mobile-ui-'));
  const browser = spawn(executable, [
    '--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-extensions', '--remote-debugging-port=0',
    '--user-data-dir=' + profile, 'about:blank'
  ], {stdio: 'ignore', windowsHide: true});
  let browserClient, client;
  t.after(async () => {
    client?.ws.close();
    if (browserClient) {
      try { await browserClient.call('Browser.close'); } catch {}
      browserClient.ws.close();
    }
    browser.kill();
    // Only this test's freshly created, isolated profile is eligible for cleanup.
    try { fs.rmSync(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100}); } catch {}
  });
  const activeFile = path.join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 100 && !fs.existsSync(activeFile); i++) {
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(fs.existsSync(activeFile), 'browser debugging endpoint started');
  const [port, socketPath] = fs.readFileSync(activeFile, 'utf8').trim().split(/\r?\n/);
  browserClient = await connect('ws://127.0.0.1:' + port + socketPath);
  const {targetId} = await browserClient.call('Target.createTarget', {url: 'about:blank'});
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  client = await connect(pages.find(page => page.id === targetId).webSocketDebuggerUrl);
  let backendAvailable = true;
  let holdProbe = false;
  let heldProbe;
  let probeCount = 0;
  const server = http.createServer((req, res) => {
    if (req.method === 'HEAD') {
      probeCount++;
      if (holdProbe) { heldProbe = res; return; }
    }
    res.writeHead(backendAvailable ? 200 : 503, { 'content-type': 'text/html' });
    res.end('<!doctype html><body></body>');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const evaluate = async expression => {
    const result = await client.call('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    assert.equal(result.exceptionDetails, undefined, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const settle = () => evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))))');
  const resize = width => client.call('Emulation.setDeviceMetricsOverride', {
    width, height: 800, deviceScaleFactor: 1, mobile: false
  });
  await resize(390);
  await client.call('Page.enable');
  await client.call('Page.navigate', {url: 'http://127.0.0.1:' + server.address().port + '/'});
  await new Promise(resolve => setTimeout(resolve, 100));
  const {frameTree} = await client.call('Page.getFrameTree');
  await client.call('Page.setDocumentContent', {frameId: frameTree.frame.id, html: `
    <!doctype html><html><head><meta name="viewport" content="width=device-width">
    <style>
      html,body{margin:0;height:100%}.fixture_frame{display:grid;height:100%;position:relative;overflow:hidden}
      .fixture_centerCol{min-width:0}.fixture_sidebarCol{min-width:0}
      .fixture_rightbarCol{min-width:0}.fixture_bottomRow{grid-column:1/-1}
    </style></head><body>
    <div class="fixture_frame" data-sidebar-collapsed data-rightbar-collapsed>
      <div class="fixture_sidebarCol"><nav aria-label="全局面板">
        <button aria-label="打开侧边栏">Menu</button>
        <div data-row-key="session:synthetic">Synthetic session<button aria-label="会话操作">More</button></div>
        <button aria-label="新建会话">New</button>
      </nav></div>
      <main class="fixture_centerCol"><textarea></textarea><input type="file" multiple></main>
      <aside class="fixture_rightbarCol"></aside><div class="fixture_bottomRow"></div>
    </div>
    <script>
      const frame=document.querySelector('.fixture_frame');
      function columns(){frame.style.gridTemplateColumns=(frame.hasAttribute('data-sidebar-collapsed')?'56px':'280px')+' minmax(0,1fr) 0px'}
      document.querySelector('nav button').onclick=()=>{frame.toggleAttribute('data-sidebar-collapsed');columns()};
      addEventListener('resize',columns);columns();
    </script></body></html>`});
  const script = fs.readFileSync(path.join(__dirname, 'res/raw/mobile_ui.js'), 'utf8');
  await evaluate('window.__dshAutoSidebar=true;' + script);
  await settle();
  const snapshot = () => evaluate(`({
    open:window.__dshSidebarOpen(),
    width:document.querySelector('.fixture_centerCol').getBoundingClientRect().width,
    screen:innerWidth,mask:getComputedStyle(document.querySelector('[data-dsh-mask]')).display
  })`);
  await t.test('collapsed conversation occupies the full screen, never zero width', async () => {
    const state = await snapshot();
    assert.equal(state.open, false);
    assert.equal(state.width, 390);
    assert.equal(state.mask, 'none');
  });
  await t.test('menu and outside tap keep the conversation full width', async () => {
    await evaluate('window.__dshToggleSidebar()');
    await settle();
    assert.deepEqual(await snapshot(), {open: true, width: 390, screen: 390, mask: 'block'});
    await evaluate("document.querySelector('[data-dsh-mask]').click()");
    await settle();
    assert.equal((await snapshot()).open, false);
    assert.equal((await snapshot()).width, 390);
  });
  await t.test('session selection closes the drawer but its action menu does not', async () => {
    await evaluate('window.__dshToggleSidebar()');
    await settle();
    await evaluate("document.querySelector('[aria-label=\"会话操作\"]').click()");
    await settle();
    assert.equal((await snapshot()).open, true);
    await evaluate("document.querySelector('[data-row-key]').click()");
    await settle();
    assert.equal((await snapshot()).open, false);
  });
  await t.test('reinjection installs no duplicate mask or style', async () => {
    await evaluate(script);
    assert.equal(await evaluate("document.querySelectorAll('[data-dsh-mask]').length"), 1);
    assert.equal(await evaluate("document.querySelectorAll('#dsh-mobile-ui').length"), 1);
  });
  await t.test('disabling auto-hide and resizing cannot blank the conversation', async () => {
    await evaluate('window.__dshSetAutoSidebar(false)');
    assert.ok((await snapshot()).width > 0);
    await resize(1024);
    await settle();
    assert.ok((await snapshot()).width > 500);
    await evaluate('window.__dshSetAutoSidebar(true)');
    await resize(360);
    await settle();
    assert.equal((await snapshot()).width, 360);
  });
  await t.test('native back closes the drawer without navigation', async () => {
    await evaluate('window.__dshToggleSidebar()');
    await settle();
    assert.equal(await evaluate('window.__dshBackAction()'), 1);
    await settle();
    assert.equal((await snapshot()).open, false);
  });
  const tick = async (auto = true, refresh = true) => {
    await evaluate(`${refresh ? 'window.__dshNetworkChanged();' : ''}window.__dshConnectionState(${auto})`);
    await new Promise(resolve => setTimeout(resolve, 100));
    return evaluate(`window.__dshConnectionState(${auto})`);
  };
  await evaluate(`window.testClock=1000;
    Object.defineProperty(performance,'now',{value:()=>window.testClock});
    window.recoveryOnlineEvents=0;
    window.recoveryOfflineEvents=0;
    window.simulateHandshake=true;
    window.unrelatedClicks=0;
    window.documentIdentity='same-document';
    document.querySelector('textarea').value='unsent synthetic draft';
    const transfer=new DataTransfer();
    transfer.items.add(new File(['synthetic'],'test.txt',{type:'text/plain'}));
    document.querySelector('input[type=file]').files=transfer.files;
    addEventListener('offline',()=>window.recoveryOfflineEvents++);
    addEventListener('online',()=>{window.recoveryOnlineEvents++;if(window.simulateHandshake)fetch('/api/health').catch(()=>{})});
    const stale=document.createElement('div');stale.role='alert';stale.textContent='连接异常 请重试';
    const button=document.createElement('button');button.textContent='重试';
    button.onclick=()=>window.unrelatedClicks++;stale.appendChild(button);document.body.appendChild(stale);`);
  await t.test('stale error text cannot override successful connectivity', async () => {
    assert.equal(await tick(), 'ok');
    assert.equal(await evaluate('window.unrelatedClicks'), 0);
  });
  await t.test('an outage automatically re-arms DSH without reload, draft loss or resending', async () => {
    backendAvailable = false;
    assert.equal(await tick(), 'bad');
    backendAvailable = true;
    await evaluate('window.testClock+=5000');
    assert.equal(await tick(), 'ok');
    assert.equal(await evaluate('window.recoveryOnlineEvents'), 1);
    assert.equal(await evaluate('window.recoveryOfflineEvents'), 1);
    assert.equal(await evaluate('window.documentIdentity'), 'same-document');
    assert.equal(await evaluate("document.querySelector('textarea').value"), 'unsent synthetic draft');
    assert.equal(await evaluate("document.querySelector('input[type=file]').files[0].name"), 'test.txt');
    assert.equal(await evaluate('window.unrelatedClicks'), 0);
  });
  await t.test('disabling auto reconnect suppresses shell recovery until re-enabled', async () => {
    backendAvailable = false;
    assert.equal(await tick(false), 'bad');
    backendAvailable = true;
    await evaluate('window.testClock+=5000');
    assert.equal(await tick(false), 'bad');
    assert.equal(await evaluate('window.recoveryOnlineEvents'), 1);
    assert.equal(await tick(true), 'ok');
    assert.equal(await evaluate('window.recoveryOnlineEvents'), 2);
  });
  await t.test('Android network callbacks can recover an already-loaded page', async () => {
    await evaluate('window.testClock+=5000;window.__dshNetworkChanged(true)');
    assert.equal(await tick(true, false), 'ok');
    assert.equal(await evaluate('window.recoveryOnlineEvents'), 3);
  });
  await t.test('concurrent polls share a probe and an old timeout cannot overwrite recovery', async () => {
    holdProbe = true;
    const before = probeCount;
    await evaluate('window.__dshNetworkChanged();for(let i=0;i<20;i++)window.__dshConnectionState(true)');
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(probeCount - before, 1);
    await evaluate("window.testClock+=1000;fetch('/api/health')");
    holdProbe = false;
    heldProbe.writeHead(503);
    heldProbe.end();
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(await tick(true, false), 'ok');
  });
  await t.test('a reachable web server alone cannot pretend the workbench session recovered', async () => {
    await evaluate('window.testClock+=1000;window.simulateHandshake=false;window.__dshNetworkChanged(true)');
    assert.equal(await tick(true, false), 'reconnecting');
    await evaluate('window.testClock+=13000');
    assert.equal(await tick(), 'reconnecting');
    await evaluate("window.simulateHandshake=true;fetch('/api/health')");
    assert.equal(await tick(), 'ok');
  });
  await t.test('only the shell reconnect action is retried and attempts are rate-limited', async () => {
    await evaluate(`window.shellRetries=0;const retry=document.createElement('button');
      retry.id='shell-retry';retry.setAttribute('aria-label','连接异常，点击立即重连');
      retry.onclick=()=>window.shellRetries++;document.querySelector('nav').appendChild(retry);`);
    assert.equal(await tick(), 'reconnecting');
    assert.equal(await evaluate('window.shellRetries'), 1);
    await evaluate('for(let i=0;i<20;i++)window.__dshConnectionState(true)');
    assert.equal(await evaluate('window.shellRetries'), 1);
    await evaluate('window.testClock+=16000');
    await tick();
    assert.equal(await evaluate('window.shellRetries'), 2);
    assert.equal(await evaluate('window.unrelatedClicks'), 0);
    await evaluate("document.querySelector('#shell-retry').remove();fetch('/api/health')");
    assert.equal(await tick(), 'ok');
  });
  await t.test('WebSocket constructor constants and subclass behavior remain available', async () => {
    assert.equal(await evaluate('WebSocket.OPEN'), 1);
    assert.equal(await evaluate('WebSocket.CLOSED'), 3);
    assert.equal(await evaluate(`(()=>{
      class Child extends WebSocket {}
      const socket=new Child('ws://127.0.0.1:${server.address().port}/unused');
      socket.addEventListener('error',()=>{});
      const valid=socket instanceof Child&&socket instanceof WebSocket;
      socket.close();return valid;
    })()`), true);
  });
});
