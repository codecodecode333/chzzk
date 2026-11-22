#!/usr/bin/env node
// 테스트 방송: puts a 방송 on air without a phone. Launches the installed Chrome or Edge headless with a fake camera and
// microphone, logs a test 회원 in (signing up when the login is refused), starts a 방송 through the site's own 송출
// screen (/broadcasts/new: title → 방송 시작, which publishes over WHIP), prints the 방송 id and keeps 송출 until Ctrl+C or
// --duration. On exit it presses 방송 종료 on the same screen. No npm dependencies: the browser is driven through the
// Chrome DevTools Protocol over Node's built-in WebSocket (Node 22+).
//
//   node scripts/test-broadcast.mjs --base-url https://localhost:5173 --insecure      # local dev (self-signed)
//   node scripts/test-broadcast.mjs --base-url https://live.codecodecode333.dev --email bot@example.com --duration 600
//
// Options (see --help): --base-url, --email, --password (or env TEST_BROADCAST_PASSWORD), --nickname, --title,
// --duration <seconds>, --watch (also play it as a logged-out 시청자 and report the list and video), --insecure
// (accept a self-signed certificate), --browser <path>, --headed.
// Without --email a fresh throwaway 회원 is signed up. Exit code 0 when the 방송 was started and ended cleanly (and,
// with --watch, the 시청자 saw it in the 방송 중 list and its video advancing).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseArgs } from 'node:util';

const USAGE = `Usage: node scripts/test-broadcast.mjs --base-url <url> [options]
  --base-url <url>      site to broadcast on, e.g. https://localhost:5173 or https://live.codecodecode333.dev
  --email <email>       test 회원 to log in as (signed up first if the login is refused); default: a new throwaway 회원
  --password <pw>       its password (8-64 chars); or env TEST_BROADCAST_PASSWORD; default: random
  --nickname <name>     닉네임 used when signing up (2-20 chars); default: derived from the email
  --title <text>        방송 title; default "테스트 방송 <time>"
  --duration <seconds>  stop by itself after this long; default: until Ctrl+C
  --watch               also open the 방송 as a logged-out 시청자: check the 방송 중 list and that video plays
  --insecure            accept an invalid/self-signed certificate (local dev only)
  --browser <path>      Chrome/Edge executable; default: env BROWSER or the installed Chrome, then Edge
  --headed              show the browser window`;

let opts;
try {
  ({ values: opts } = parseArgs({
    options: {
      'base-url': { type: 'string' }, email: { type: 'string' }, password: { type: 'string' },
      nickname: { type: 'string' }, title: { type: 'string' }, duration: { type: 'string' },
      watch: { type: 'boolean' }, insecure: { type: 'boolean' }, browser: { type: 'string' },
      headed: { type: 'boolean' }, help: { type: 'boolean', short: 'h' },
    },
  }));
} catch (e) {
  console.error(`${e.message}\n\n${USAGE}`);
  process.exit(2);
}
if (opts.help || !opts['base-url']) {
  console.log(USAGE);
  process.exit(opts.help ? 0 : 2);
}
const BASE = new URL(opts['base-url']).origin;
const DURATION = opts.duration === undefined ? Infinity : Number(opts.duration) * 1000;
if (!(DURATION > 0)) {
  console.error('--duration must be a positive number of seconds');
  process.exit(2);
}
const rand = Math.random().toString(36).slice(2, 8);
const EMAIL = opts.email ?? `test-broadcast-${rand}@example.test`;
const PASSWORD = opts.password ?? process.env.TEST_BROADCAST_PASSWORD ?? `tb-${crypto.randomUUID()}`;
const NICKNAME = opts.nickname ?? (opts.email ? EMAIL.split('@')[0].slice(0, 20).padEnd(2, '_') : `테스트방송${rand.slice(0, 4)}`);
const TITLE = opts.title ?? `테스트 방송 ${new Date().toTimeString().slice(0, 5)}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (msg) => console.log(`[${new Date().toTimeString().slice(0, 8)}] ${msg}`);

function findBrowser() {
  const candidates = [
    opts.browser, process.env.BROWSER,
    ...(process.platform === 'win32' ? [
      `${process.env.PROGRAMFILES}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env['PROGRAMFILES(X86)']}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
      `${process.env['PROGRAMFILES(X86)']}\\Microsoft\\Edge\\Application\\msedge.exe`,
      `${process.env.PROGRAMFILES}\\Microsoft\\Edge\\Application\\msedge.exe`,
    ] : process.platform === 'darwin' ? [
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    ] : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser',
      '/usr/bin/microsoft-edge']),
  ];
  const found = candidates.find((p) => p && existsSync(p));
  if (!found) throw new Error('Chrome or Edge not found; pass --browser <path>');
  return found;
}

// ---- Browser over the DevTools protocol: one browser-level WebSocket, one flattened session per page. ----

const profile = mkdtempSync(join(tmpdir(), 'test-broadcast-'));
let browser; // child process
let cdp; // { send, on }

async function launch() {
  const exe = findBrowser();
  const args = [
    `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check',
    '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required',
    // Keep 송출 at full rate in pages that aren't in front.
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
    ...(opts.headed ? [] : ['--headless=new']),
    ...(opts.insecure ? ['--ignore-certificate-errors'] : []),
    'about:blank',
  ];
  // Own process group: Ctrl+C reaches only this script, which then ends the 방송 before closing the browser.
  browser = spawn(exe, args, { stdio: 'ignore', detached: true });
  browser.on('exit', () => { browser = null; });
  const portFile = join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 100 && !existsSync(portFile); i++) await sleep(100);
  if (!existsSync(portFile)) throw new Error(`${exe} did not start`);
  await sleep(100); // the file may be written in two steps
  const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n');
  const ws = new WebSocket(`ws://127.0.0.1:${port}${path}`);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve);
    ws.addEventListener('error', () => reject(new Error('could not connect to the browser')));
  });
  let seq = 0;
  const pending = new Map();
  const listeners = [];
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) reject(new Error(`${m.error.message}`));
      else resolve(m.result);
    } else if (m.method) {
      for (const l of listeners) l(m);
    }
  });
  ws.addEventListener('close', () => {
    for (const { reject } of pending.values()) reject(new Error('browser connection closed'));
    pending.clear();
  });
  cdp = {
    send: (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      if (ws.readyState !== WebSocket.OPEN) return reject(new Error('browser connection closed'));
      const id = ++seq;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params, sessionId }));
    }),
    on: (l) => listeners.push(l),
    close: () => ws.close(),
  };
  log(`browser: ${exe}${opts.headed ? '' : ' (headless)'}`);
}

/** A page (tab); `isolated` gives it its own cookie jar, i.e. a logged-out 시청자. */
async function openPage({ isolated = false } = {}) {
  const { browserContextId } = isolated ? await cdp.send('Target.createBrowserContext') : {};
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  const send = (method, params) => cdp.send(method, params, sessionId);
  const errors = [];
  cdp.on((m) => {
    if (m.sessionId !== sessionId) return;
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text);
  });
  await send('Runtime.enable');
  await send('Page.enable');
  // Auto-accept window.confirm (방송 종료 asks "방송을 종료할까요?") and skip the leave-page prompt.
  cdp.on((m) => {
    if (m.sessionId === sessionId && m.method === 'Page.javascriptDialogOpening') {
      send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
    }
  });

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
    return r.result.value;
  };
  const until = async (expression, what, ms = 20000) => {
    const end = Date.now() + ms;
    for (;;) {
      const v = await evaluate(expression).catch(() => null);
      if (v) return v;
      if (Date.now() > end) {
        const text = await evaluate('document.body?.innerText.slice(0, 500)').catch(() => '?');
        throw new Error(`timed out waiting for ${what} on ${await evaluate('location.href').catch(() => '?')}\n`
          + `page text: ${JSON.stringify(text)}${errors.length ? `\npage errors: ${errors.join(' | ')}` : ''}`);
      }
      await sleep(250);
    }
  };
  const goto = async (url) => {
    const r = await send('Page.navigate', { url });
    if (r.errorText) throw new Error(`could not open ${url}: ${r.errorText}${r.errorText.includes('CERT') ? ' (self-signed? add --insecure)' : ''}`);
    await until(`document.readyState === 'complete' && location.href !== 'about:blank'`, `${url} to load`);
  };
  return { evaluate, until, goto };
}

// In-page helpers, evaluated in the site's own origin so its cookies (session, XSRF-TOKEN) are the browser's.
const PAGE_HELPERS = `
  const button = (text) => [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === text);
  const xsrf = async () => {
    const cookie = () => document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice(11);
    if (!cookie()) await fetch('/api/auth/csrf');
    return decodeURIComponent(cookie() ?? '');
  };
  const post = async (path, body) => fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'X-XSRF-TOKEN': await xsrf() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
`;
const inPage = (body) => `(async () => { ${PAGE_HELPERS} ${body} })()`;
const setTitleInput = (title) => inPage(`
  const input = document.querySelector('#broadcast-title');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(title)});
  input.dispatchEvent(new Event('input', { bubbles: true }));
`);

// ---- The flow ----

let studio = null;
let broadcastId = null;
let ended = false;

async function logIn(page) {
  const result = await page.evaluate(inPage(`
    const creds = ${JSON.stringify({ email: EMAIL, password: PASSWORD })};
    const login = await post('/api/auth/login', creds);
    if (login.ok) return 'logged in';
    if (login.status !== 401) return 'login failed: HTTP ' + login.status + ' ' + await login.text();
    const signup = await post('/api/auth/signup', { ...creds, nickname: ${JSON.stringify(NICKNAME)} });
    if (signup.ok) return 'signed up';
    return 'login refused and signup failed: HTTP ' + signup.status + ' ' + await signup.text();
  `));
  if (!result.startsWith('logged in') && !result.startsWith('signed up')) throw new Error(result);
  const me = await page.evaluate(`fetch('/api/me').then((r) => r.ok ? r.json() : null)`);
  if (!me) throw new Error('logged in, but /api/me does not know the session (cookies blocked?)');
  log(`회원 ${me.nickname} (${EMAIL}) ${result}`);
}

/** 송출 screen: wait for the camera, enter the title, 방송 시작; ends a leftover 방송 중 방송 of this 회원 first. */
async function startBroadcast() {
  studio = await openPage();
  await studio.goto(`${BASE}/`);
  await logIn(studio);
  await studio.goto(`${BASE}/broadcasts/new`);
  await studio.until(`document.querySelector('[data-testid=camera-preview]')?.srcObject?.getVideoTracks().length > 0`,
    'the fake camera preview');
  for (let attempt = 0; attempt < 2; attempt++) {
    await studio.until(inPage(`return !!document.querySelector('#broadcast-title') || !!button('새 방송으로 시작')`),
      'the 방송 시작 form');
    if (await studio.evaluate(inPage(`return !!button('새 방송으로 시작')`))) {
      log('this 회원 already has a 방송 중 방송: ending it (새 방송으로 시작)');
      await studio.evaluate(inPage(`button('새 방송으로 시작').click()`));
      await studio.until(`!!document.querySelector('#broadcast-title')`, 'the 방송 시작 form after 새 방송으로 시작');
    }
    await studio.evaluate(setTitleInput(TITLE));
    await studio.until(inPage(`return button('방송 시작')?.disabled === false`), 'the 방송 시작 button to enable');
    await studio.evaluate(inPage(`button('방송 시작').click()`));
    const outcome = await studio.until(inPage(`
      const watch = [...document.querySelectorAll('a')].find((a) => a.textContent.includes('시청 링크'));
      if (watch && button('방송 종료')) return 'live:' + watch.getAttribute('href');
      if (button('새 방송으로 시작')) return 'resumable';
      const alert = [...document.querySelectorAll('[role=alert]')].map((a) => a.textContent).join(' ');
      return alert && !button('방송 시작')?.disabled ? 'error:' + alert : null;
    `), '방송 시작 to finish', 30000);
    if (outcome.startsWith('live:')) {
      broadcastId = Number(outcome.slice('live:/broadcasts/'.length));
      return;
    }
    if (outcome.startsWith('error:')) throw new Error(`방송 시작 failed: ${outcome.slice(6)}`);
    // 'resumable': a 409 because a 방송 중 방송 exists; the next attempt ends it first.
  }
  throw new Error('방송 시작 kept finding a 방송 중 방송');
}

/** 방송 종료 on the 송출 screen (confirm is auto-accepted); falls back to the API with the same session. */
async function endBroadcast() {
  if (broadcastId === null || ended) return;
  ended = true;
  try {
    await studio.evaluate(inPage(`button('방송 종료').click()`));
    await studio.until(`document.body.innerText.includes('종료됨')`, '종료됨', 15000);
    log(`방송 ${broadcastId} ended (방송 종료)`);
  } catch (e) {
    const status = await studio?.evaluate(inPage(`return (await post('/api/broadcasts/${broadcastId}/end')).status`)).catch(() => null);
    if (status === 204 || status === 200) log(`방송 ${broadcastId} ended (via the API: ${e.message.split('\n')[0]})`);
    else {
      log(`could not end 방송 ${broadcastId} (${e.message.split('\n')[0]}); the server auto-ends it about 70s after media stops`);
      process.exitCode = 1;
    }
  }
}

/** A logged-out 시청자 in its own cookie jar: the 방송 is in the 방송 중 list and its video plays. */
async function watch() {
  const viewer = await openPage({ isolated: true });
  await viewer.goto(`${BASE}/`);
  await viewer.until(`[...document.querySelectorAll('a[href="/broadcasts/${broadcastId}"]')].some((a) => a.innerText.includes(${JSON.stringify(TITLE)}))`,
    `방송 ${broadcastId} in the 방송 중 list`);
  const listed = await viewer.evaluate(`fetch('/api/broadcasts?status=LIVE&size=100').then((r) => r.json())
    .then((p) => p.content.some((b) => b.id === ${broadcastId}))`);
  log(`시청자: 방송 ${broadcastId} is in the 방송 중 list${listed ? ' (API status=LIVE too)' : ' (but not in API status=LIVE!)'}`);
  await viewer.goto(`${BASE}/broadcasts/${broadcastId}`);
  const size = await viewer.until(`(() => { const v = document.querySelector('[data-testid=live-player]');
    return v && v.readyState >= 2 && v.videoWidth > 0 ? v.videoWidth + 'x' + v.videoHeight : null })()`,
  'the 시청자 video to start', 30000);
  const time = () => viewer.evaluate(`document.querySelector('[data-testid=live-player]').currentTime`);
  const t0 = await time();
  await sleep(3000);
  const t1 = await time();
  const frames = await viewer.evaluate(`document.querySelector('[data-testid=live-player]').getVideoPlaybackQuality?.().totalVideoFrames ?? 0`);
  const ok = listed && t1 - t0 > 2;
  log(`시청자: video ${size}, currentTime ${t0.toFixed(1)}s → ${t1.toFixed(1)}s over 3s, ${frames} frames decoded — ${ok ? 'OK' : 'NOT PLAYING'}`);
  if (!ok) process.exitCode = 1;
}

/** While on air: report a dropped publication and press 다시 연결 (the screen's own recovery). */
async function keepOnAir(until) {
  while (Date.now() < until && !stopping) {
    await sleep(Math.min(5000, Math.max(0, until - Date.now())));
    if (stopping) break;
    const state = await studio.evaluate(inPage(`
      if (document.body.innerText.includes('종료됨')) return 'ended';
      if (button('다시 연결') && !button('다시 연결').disabled) { button('다시 연결').click(); return 'reconnecting'; }
      if (button('여기서 이어서 송출')) return 'taken-over';
      return 'live';
    `)).catch((e) => `error: ${e.message}`);
    if (state === 'live') continue;
    if (state === 'reconnecting') log('송출 dropped; pressed 다시 연결');
    else if (state === 'ended') { log(`방송 ${broadcastId} was ended elsewhere`); ended = true; process.exitCode = 1; return; }
    else if (state === 'taken-over') { log('another tab/device took over this 방송; stopping'); process.exitCode = 1; return; }
    else { log(`송출 screen unreachable (${state}); stopping`); process.exitCode = 1; return; }
  }
}

let stopping = false;
let wake = () => {};
async function shutdown() {
  try { await endBroadcast(); } catch { /* reported in endBroadcast */ }
  try { await cdp?.send('Browser.close'); } catch { /* already gone */ }
  cdp?.close();
  for (let i = 0; i < 30 && browser; i++) await sleep(100);
  browser?.kill();
  for (let i = 0; i < 20; i++) {
    try { rmSync(profile, { recursive: true, force: true }); break; } catch { await sleep(250); } // files held until exit
  }
}
for (const signal of ['SIGINT', 'SIGTERM', 'SIGBREAK']) {
  process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    log(`${signal}: ending the 방송…`);
    wake();
  });
}

try {
  await launch();
  await startBroadcast();
  log(`방송 ${broadcastId} 방송 중: "${TITLE}" — ${BASE}/broadcasts/${broadcastId}`);
  console.log(`BROADCAST_ID=${broadcastId}`);
  if (opts.watch) await watch();
  log(Number.isFinite(DURATION) ? `송출 for ${DURATION / 1000}s (Ctrl+C to stop early)` : '송출 until Ctrl+C');
  await Promise.race([keepOnAir(Date.now() + DURATION), new Promise((r) => { wake = r; })]);
} catch (e) {
  console.error(`error: ${e.message}`);
  process.exitCode = 1;
} finally {
  await shutdown();
}
process.exit();
