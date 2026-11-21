// Load for cluster mode: mixed public reads (방송 list, VOD list, 댓글) against the HTTPS entry point.
// Run through the pinned grafana/k6 image (no install), see load/run-k6.sh.
//   BASE_URL  target (default https://host.docker.internal:8443, the kind entry seen from a container)
//   VUS       virtual users during the hold (default 60)
//   RAMP      ramp-up duration (default 30s)
//   HOLD      hold duration (default 3m)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'https://host.docker.internal:8443';
const VUS = parseInt(__ENV.VUS || '60', 10);

export const options = {
  insecureSkipTLSVerify: true, // self-signed certificate per nginx pod
  scenarios: {
    reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || '30s', target: VUS },
        { duration: __ENV.HOLD || '3m', target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: { checks: ['rate>0.99'] },
};

// Ids to read 댓글 from, picked once from whatever is in the cluster.
export function setup() {
  const live = http.get(`${BASE}/api/broadcasts?status=LIVE&size=5`).json('content') || [];
  const ended = http.get(`${BASE}/api/broadcasts?status=ENDED&size=5`).json('content') || [];
  const vods = http.get(`${BASE}/api/vods?size=5`).json('content') || [];
  return {
    broadcastIds: [...live, ...ended].map((b) => b.id),
    vodIds: vods.map((v) => v.id),
  };
}

const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

export default function (data) {
  const reqs = [
    ['GET', `${BASE}/api/broadcasts?status=${Math.random() < 0.5 ? 'LIVE' : 'ENDED'}`],
    ['GET', `${BASE}/api/vods`],
  ];
  if (data.broadcastIds.length) reqs.push(['GET', `${BASE}/api/broadcasts/${pick(data.broadcastIds)}/comments`]);
  if (data.vodIds.length) reqs.push(['GET', `${BASE}/api/vods/${pick(data.vodIds)}/comments`]);
  for (const res of http.batch(reqs)) {
    check(res, { 'status 200': (r) => r.status === 200 });
  }
}
