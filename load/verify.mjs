// Holds one logged-in session against the cluster while it scales and prints, every tick:
// time, /api/me result, 방송 count (방송 중 + 종료), 댓글 count on its own 방송, a byte range of its own VOD's video,
// X-Served-By of those calls, backend pod count.
// Seeds its own 회원, 방송, 댓글 and VOD (a small generated MP4) first, noting which pod registered the VOD; each tick
// fetches a different byte range of that VOD's video and checks it byte for byte, so a VOD registered via one pod is
// seen playing via the others (the shared 영상 파일 PVC). Ends the 방송 and deletes the VOD on exit.
// The 방송 sends no media, so the server auto-ends it after about a minute (BroadcastLifecycle.AUTO_END_AFTER): that's why 방송 중 and 종료 are counted together, as
// distinct ids (first 100 of each; the demo cluster has far fewer).
//
//   node load/verify.mjs                 # until Ctrl+C
//   DURATION=420 node load/verify.mjs    # seconds
// Env: BASE (default https://localhost:8443), INTERVAL seconds (default 5), KUBE_CONTEXT (default kind-chzzk),
//      NAMESPACE (default chzzk). Exit code 0 only if every tick matched the first one, the session held, and the
//      VOD played every tick, at least once via a pod other than the one that registered it.
import { execFile } from 'node:child_process';

process.env.NODE_TLS_REJECT_UNAUTHORIZED ??= '0'; // self-signed entry certificate
process.removeAllListeners('warning');

const BASE = process.env.BASE ?? 'https://localhost:8443';
const INTERVAL = Number(process.env.INTERVAL ?? 5) * 1000;
const DURATION = process.env.DURATION ? Number(process.env.DURATION) * 1000 : Infinity;
const CTX = process.env.KUBE_CONTEXT ?? 'kind-chzzk';
const NS = process.env.NAMESPACE ?? 'chzzk';
const COMMENTS = 3;
const VOD_BYTES = 256 * 1024;
const RANGE_BYTES = 4096;

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

// A small "MP4": an ftyp box (all the backend checks besides the .mp4 name) followed by a known byte pattern.
function videoBytes() {
  const bytes = new Uint8Array(VOD_BYTES);
  bytes.set([0, 0, 0, 24, ...Buffer.from('ftypisom'), 0, 0, 2, 0, ...Buffer.from('isommp41')]);
  for (let i = 24; i < bytes.length; i++) bytes[i] = (i * 31 + (i >> 8)) & 0xff;
  return bytes;
}
async function upload(path, fields, file, name) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  form.append('file', new Blob([file], { type: 'video/mp4' }), name);
  const headers = { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), 'X-XSRF-TOKEN': jar.get('XSRF-TOKEN') };
  const res = await fetch(BASE + path, { method: 'POST', headers, body: form });
  const text = await res.text();
  return { status: res.status, by: res.headers.get('x-served-by') ?? '-', body: text ? JSON.parse(text) : null };
}
// One byte range of the VOD's video, checked against what was uploaded.
async function playRange(n) {
  const start = (n * 7919 * RANGE_BYTES) % (VOD_BYTES - RANGE_BYTES);
  const end = start + RANGE_BYTES - 1;
  const res = await fetch(BASE + vod.streamUrl, {
    headers: { range: `bytes=${start}-${end}`, cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') },
  });
  const got = new Uint8Array(await res.arrayBuffer());
  const ok = res.status === 206 && got.length === RANGE_BYTES && Buffer.from(got).equals(Buffer.from(video.subarray(start, end + 1)));
  return { ok: ok ? 'OK' : `HTTP ${res.status} ${got.length}B`, by: res.headers.get('x-served-by') ?? '-' };
}

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
const video = videoBytes();
const registered = await upload('/api/vods', { title: `verify ${id}` }, video, `verify-${id}.mp4`);
const vod = must(registered, 201, 'vod register');
console.log(`seeded 회원 ${nickname}, 방송 ${broadcast.id} (방송 중), 댓글 ${COMMENTS}개, `
  + `VOD ${vod.id} (${VOD_BYTES}B, 등록: ${registered.by}) — ${BASE}`);
console.log('time      me                      방송(전체)  댓글  VOD  X-Served-By (me / 방송 / 댓글 / VOD)  pods(ready/all)');

const ticks = [];
let errors = 0;
async function tick() {
  const [me, list, ended, comments, play, podCount] = await Promise.all([
    call('GET', '/api/me'),
    call('GET', '/api/broadcasts?status=LIVE&size=100'),
    call('GET', '/api/broadcasts?status=ENDED&size=100'),
    call('GET', `/api/broadcasts/${broadcast.id}/comments`),
    playRange(ticks.length),
    pods(),
  ]);
  const t = {
    me: me.status === 200 ? `200 ${me.body.nickname}` : `${me.status} LOGGED OUT`,
    // distinct ids: a 방송 auto-ended between the two requests shows up in both lists
    live: list.status === 200 && ended.status === 200
      ? new Set([...list.body.content, ...ended.body.content].map((b) => b.id)).size
      : `HTTP ${list.status}/${ended.status}`,
    comments: comments.status === 200 ? comments.body.content.length : `HTTP ${comments.status}`,
    vod: play.ok,
    served: [me.by, list.by, comments.by, play.by],
    pods: podCount,
  };
  ticks.push(t);
  console.log(`${time()}  ${t.me.padEnd(22)}  ${String(t.live).padStart(10)}  ${String(t.comments).padStart(4)}  `
    + `${t.vod.padStart(3)}  ${t.served.map(short).join(' / ').padEnd(36)}  ${t.pods}`);
}

function summary() {
  const first = ticks[0];
  const loggedIn = ticks.every((t) => t.me === first.me && t.me.startsWith('200'));
  const sameLive = ticks.every((t) => t.live === first.live);
  const sameComments = ticks.every((t) => t.comments === COMMENTS);
  const vodPlays = ticks.every((t) => t.vod === 'OK');
  const vodPods = [...new Set(ticks.map((t) => t.served[3]))].filter((s) => s !== '-');
  const vodOtherPods = vodPods.filter((s) => s !== registered.by);
  const servedBy = [...new Set(ticks.flatMap((t) => t.served))].filter((s) => s !== '-');
  const podCounts = [...new Set(ticks.map((t) => t.pods))];
  console.log(`\n${ticks.length} ticks: 로그인 유지 ${loggedIn ? 'OK' : 'FAIL'}, 방송 수 그대로 ${sameLive ? 'OK' : 'FAIL'}`
    + ` (${first.live}), 댓글 수 그대로 ${sameComments ? 'OK' : 'FAIL'} (${COMMENTS}), VOD 재생 ${vodPlays ? 'OK' : 'FAIL'}`);
  console.log(`VOD ${vod.id}: ${registered.by}에서 등록, 재생한 파드 ${vodPods.length}개 (등록 파드 외 ${vodOtherPods.length}개): `
    + vodPods.join(', '));
  console.log(`X-Served-By ${servedBy.length}개: ${servedBy.join(', ')}`);
  console.log(`pods seen: ${podCounts.join(', ')}`);
  if (errors) console.log(`errors: ${errors}`);
  return errors === 0 && loggedIn && sameLive && sameComments && vodPlays && vodOtherPods.length > 0;
}

let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  const ok = summary();
  await call('POST', `/api/broadcasts/${broadcast.id}/end`).catch(() => {});
  await call('DELETE', `/api/vods/${vod.id}`).catch(() => {});
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
