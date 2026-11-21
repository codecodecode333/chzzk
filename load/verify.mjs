// Holds one logged-in session against the cluster while it scales and prints, every tick:
// time, /api/me result, 방송 count (방송 중 + 종료), 댓글 count on its own 방송, X-Served-By of those calls, backend pod count.
// Seeds its own 회원, 방송 and 댓글 first; ends the 방송 on exit. The 방송 sends no media, so the server auto-ends it
// after about a minute (BroadcastLifecycle.AUTO_END_AFTER): that's why 방송 중 and 종료 are counted together.
//
//   node load/verify.mjs                 # until Ctrl+C
//   DURATION=420 node load/verify.mjs    # seconds
// Env: BASE (default https://localhost:8443), INTERVAL seconds (default 5), KUBE_CONTEXT (default kind-chzzk),
//      NAMESPACE (default chzzk). Exit code 0 only if every tick matched the first one and the session held.
import { execFile } from 'node:child_process';

process.env.NODE_TLS_REJECT_UNAUTHORIZED ??= '0'; // self-signed entry certificate
process.removeAllListeners('warning');

const BASE = process.env.BASE ?? 'https://localhost:8443';
const INTERVAL = Number(process.env.INTERVAL ?? 5) * 1000;
const DURATION = process.env.DURATION ? Number(process.env.DURATION) * 1000 : Infinity;
const CTX = process.env.KUBE_CONTEXT ?? 'kind-chzzk';
const NS = process.env.NAMESPACE ?? 'chzzk';
const COMMENTS = 3;

const jar = new Map();
async function call(method, path, json) {
  const headers = { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') };
  if (method !== 'GET' && jar.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = jar.get('XSRF-TOKEN');
  if (json !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(BASE + path, { method, headers, body: json === undefined ? undefined : JSON.stringify(json) });
  for (const c of res.headers.getSetCookie()) {
    const [kv] = c.split(';');
    const i = kv.indexOf('=');
    jar.set(kv.slice(0, i), kv.slice(i + 1));
  }
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, by: res.headers.get('x-served-by') ?? '-', body };
}
function must(r, status, what) {
  if (r.status !== status) throw new Error(`${what}: HTTP ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
}
const pods = () => new Promise((resolve) => {
  execFile('kubectl', ['--context', CTX, '-n', NS, 'get', 'deploy', 'backend',
    '-o', 'jsonpath={.status.readyReplicas}/{.status.replicas}'], (err, out) => resolve(err ? '?' : out.trim()));
});
const short = (pod) => pod.replace(/^backend-[a-z0-9]+-/, ''); // backend-6dd89d6b57-kq2bv -> kq2bv
const time = () => new Date().toTimeString().slice(0, 8);

// Seed: 회원 (signup logs in), 방송 중 방송, 댓글.
await call('GET', '/api/auth/csrf'); // XSRF-TOKEN cookie
const id = Date.now().toString(36);
const nickname = `v${id}`.slice(0, 20);
must(await call('POST', '/api/auth/signup', { email: `${nickname}@example.test`, password: 'verify-pass-1', nickname }),
  201, 'signup');
const broadcast = must(await call('POST', '/api/broadcasts', { title: `verify ${id}` }), 201, 'broadcast start');
for (let i = 1; i <= COMMENTS; i++) {
  must(await call('POST', `/api/broadcasts/${broadcast.id}/comments`, { content: `유지 확인 댓글 ${i}` }), 201, 'comment');
}
console.log(`seeded 회원 ${nickname}, 방송 ${broadcast.id} (방송 중), 댓글 ${COMMENTS}개 — ${BASE}`);
console.log('time      me                      방송(전체)  댓글  X-Served-By (me / 방송 / 댓글)  pods(ready/all)');

const ticks = [];
let errors = 0;
async function tick() {
  const [me, list, ended, comments, podCount] = await Promise.all([
    call('GET', '/api/me'),
    call('GET', '/api/broadcasts?status=LIVE'),
    call('GET', '/api/broadcasts?status=ENDED'),
    call('GET', `/api/broadcasts/${broadcast.id}/comments`),
    pods(),
  ]);
  const t = {
    me: me.status === 200 ? `200 ${me.body.nickname}` : `${me.status} LOGGED OUT`,
    live: list.status === 200 && ended.status === 200 ? list.body.totalElements + ended.body.totalElements
      : `HTTP ${list.status}/${ended.status}`,
    comments: comments.status === 200 ? comments.body.content.length : `HTTP ${comments.status}`,
    served: [me.by, list.by, comments.by],
    pods: podCount,
  };
  ticks.push(t);
  console.log(`${time()}  ${t.me.padEnd(22)}  ${String(t.live).padStart(10)}  ${String(t.comments).padStart(4)}  `
    + `${t.served.map(short).join(' / ').padEnd(30)}  ${t.pods}`);
}

function summary() {
  const first = ticks[0];
  const loggedIn = ticks.every((t) => t.me === first.me && t.me.startsWith('200'));
  const sameLive = ticks.every((t) => t.live === first.live);
  const sameComments = ticks.every((t) => t.comments === COMMENTS);
  const servedBy = [...new Set(ticks.flatMap((t) => t.served))].filter((s) => s !== '-');
  const podCounts = [...new Set(ticks.map((t) => t.pods))];
  console.log(`\n${ticks.length} ticks: 로그인 유지 ${loggedIn ? 'OK' : 'FAIL'}, 방송 수 그대로 ${sameLive ? 'OK' : 'FAIL'}`
    + ` (${first.live}), 댓글 수 그대로 ${sameComments ? 'OK' : 'FAIL'} (${COMMENTS})`);
  console.log(`X-Served-By ${servedBy.length}개: ${servedBy.join(', ')}`);
  console.log(`pods seen: ${podCounts.join(', ')}`);
  if (errors) console.log(`errors: ${errors}`);
  return errors === 0 && loggedIn && sameLive && sameComments;
}

let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  const ok = summary();
  await call('POST', `/api/broadcasts/${broadcast.id}/end`).catch(() => {});
  process.exit(ok ? 0 : 1);
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);

const started = Date.now();
while (!stopping && Date.now() - started < DURATION) {
  const next = Date.now() + INTERVAL;
  await tick().catch((e) => { errors++; console.log(`${time()}  ERROR ${e.message}`); });
  await new Promise((r) => setTimeout(r, Math.max(0, next - Date.now())));
}
await stop();
