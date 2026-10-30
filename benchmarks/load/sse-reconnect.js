import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// RULES-mode Java-only contract: each iteration persists a Run, disconnects after
// the first completed stream response, then resumes from its first event ID.
const reconnectValid = new Rate('sse_reconnect_consistent');
const reconnectLatency = new Trend('sse_reconnect_latency', true);
const recovered = new Rate('sse_runtime_resources_recovered');
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const rate = Number(__ENV.RATE || '1');
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || '5');
const maxVUs = Number(__ENV.MAX_VUS || '10');
const p95 = Number(__ENV.P95_MS || '10000');
const recoveryTimeoutSeconds = Number(__ENV.RECOVERY_TIMEOUT_SECONDS || '30');

if (![rate, preAllocatedVUs, maxVUs].every(Number.isInteger)
    || rate < 1 || preAllocatedVUs < 1 || maxVUs < preAllocatedVUs
    || !Number.isFinite(p95) || p95 <= 0
    || !Number.isFinite(recoveryTimeoutSeconds) || recoveryTimeoutSeconds < 1 || recoveryTimeoutSeconds > 300) {
  throw new Error('Invalid SSE reconnect load test configuration');
}

export const options = {
  teardownTimeout: `${recoveryTimeoutSeconds + 10}s`,
  scenarios: {
    sse_reconnect: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '70s',
    },
  },
  thresholds: {
    sse_reconnect_consistent: ['rate==1'],
    sse_reconnect_latency: [`p(95)<${p95}`],
    sse_runtime_resources_recovered: ['rate==1'],
    dropped_iterations: ['count==0'],
    checks: ['rate==1'],
  },
};

function parseFrames(body) {
  return String(body || '').split(/\r?\n\r?\n/).flatMap((frame) => {
    let id;
    let event = 'message';
    const data = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('id:')) id = line.slice(3).trim();
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
    }
    if (!data.length) return [];
    try { return [{ id, event, data: JSON.parse(data.join('\n')) }]; } catch (_) { return []; }
  });
}

function headers() {
  const value = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
  if (__ENV.JAVA_INTERNAL_SERVICE_TOKEN) value['X-Internal-Service-Token'] = __ENV.JAVA_INTERNAL_SERVICE_TOKEN;
  return value;
}

export default function () {
  const runId = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const scene = ['homepage', 'campaign', 'retention'][exec.scenario.iterationInTest % 3];
  const request = {
    runId,
    request: {
      userId: `k6-user-${__VU}`,
      scene,
      numItems: 2,
      platform: 'shopify',
      region: 'SEA',
      country: 'SG',
      locale: 'en-SG',
      currency: 'SGD',
    },
  };
  const startedAt = Date.now();
  const original = http.post(`${baseUrl}/api/v1/recommend/agent-loop/stream`, JSON.stringify(request), {
    headers: headers(),
    timeout: __ENV.REQUEST_TIMEOUT || '70s',
    tags: { name: 'recommend_agent_loop_stream', scene },
  });
  const originalFrames = parseFrames(original.body);
  const originalIds = originalFrames.map((frame) => frame.id).filter(Boolean);
  const firstId = originalIds[0];
  const runObserved = originalFrames.some((frame) => frame.event === 'run.completed' || frame.event === 'run.failed');
  const initialValid = original.status === 200 && firstId && runObserved
    && originalIds.length > 0 && new Set(originalIds).size === originalIds.length;

  let resumedValid = false;
  if (initialValid) {
    const resumeHeaders = headers();
    resumeHeaders['Last-Event-ID'] = firstId;
    const resumed = http.get(`${baseUrl}/api/v1/agent-runs/${encodeURIComponent(runId)}/events`, {
      headers: resumeHeaders,
      timeout: __ENV.REQUEST_TIMEOUT || '70s',
      tags: { name: 'agent_run_sse_resume', scene },
    });
    const resumedIds = parseFrames(resumed.body).map((frame) => frame.id).filter(Boolean);
    resumedValid = resumed.status === 200
      && resumedIds.join('|') === originalIds.slice(1).join('|')
      && new Set(resumedIds).size === resumedIds.length;
  }

  const valid = Boolean(initialValid && resumedValid);
  reconnectValid.add(valid);
  reconnectLatency.add(Date.now() - startedAt);
  check({ valid }, { 'SSE reconnect resumes the exact ordered suffix without duplicates': (value) => value.valid });
  if (!valid) console.warn(`SSE reconnect mismatch: status=${original.status}, scene=${scene}, iteration=${exec.scenario.iterationInTest}`);
}

export function teardown() {
  const deadline = Date.now() + recoveryTimeoutSeconds * 1000;
  const requestHeaders = headers();
  let healthy = false;
  do {
    const response = http.get(`${baseUrl}/api/v1/metrics`, { headers: requestHeaders, timeout: '5s', tags: { name: 'sse_resource_recovery' } });
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    const guard = body?.runtime_guard;
    const queue = body?.recommendation_persistence;
    const events = body?.recommendation_events;
    healthy = response.status === 200 && guard?.active_runs === 0
      && guard.available_permits === guard.max_concurrent_runs
      && queue?.runningRuns === 0 && queue.runningTasks === 0 && queue.pendingTasks === 0
      && queue.outboxPending === 0 && queue.outboxInFlight === 0 && queue.outboxDeadLetter === 0
      && events?.subscribers === 0 && events.activeSenders === 0 && events.queuedSends === 0;
    if (healthy) break;
    sleep(1);
  } while (Date.now() < deadline);
  recovered.add(healthy);
}
