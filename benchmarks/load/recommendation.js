import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// Internal-service load test, not an authentication or recommendation relevance benchmark.
const success = new Rate('recommendation_success');
const rejected = new Rate('recommendation_rejected');
const safety = new Rate('recommendation_business_invariants');
const latency = new Trend('recommendation_latency', true);
const recovered = new Rate('runtime_recovered_after_load');
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const minSuccess = Number(__ENV.MIN_SUCCESS_RATE || '0.99');
const rate = Number(__ENV.RATE || '5');
const p95 = Number(__ENV.P95_MS || '5000');
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || '10');
const maxVUs = Number(__ENV.MAX_VUS || '50');
const maxRejection = Number(__ENV.MAX_REJECTION_RATE || '0.01');
const recoveryTimeoutSeconds = Number(__ENV.RECOVERY_TIMEOUT_SECONDS || '30');
if (![rate, preAllocatedVUs, maxVUs].every(n => Number.isInteger(n) && n > 0)
    || maxVUs < preAllocatedVUs || !Number.isFinite(p95) || p95 <= 0
    || !Number.isFinite(minSuccess) || minSuccess <= 0 || minSuccess > 1
    || !Number.isFinite(maxRejection) || maxRejection < 0 || maxRejection > 1
    || !Number.isFinite(recoveryTimeoutSeconds) || recoveryTimeoutSeconds < 1 || recoveryTimeoutSeconds > 300) {
  throw new Error('Invalid load test configuration');
}

export const options = {
  teardownTimeout: `${recoveryTimeoutSeconds + 10}s`,
  scenarios: {
    recommendation: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs, maxVUs,
      gracefulStop: '70s',
    },
  },
  thresholds: {
    recommendation_success: [`rate>=${minSuccess}`],
    recommendation_rejected: [`rate<=${maxRejection}`],
    recommendation_business_invariants: ['rate==1'],
    recommendation_latency: [`p(95)<${p95}`],
    // Missing throughput is a failure, even if the surviving requests are fast.
    dropped_iterations: ['count==0'],
    checks: ['rate==1'],
    runtime_recovered_after_load: ['rate==1'],
  },
};

export default function () {
  const scene = ['homepage', 'campaign', 'retention'][exec.scenario.iterationInTest % 3];
  const requestedItems = 3;
  const request = {
    userId: `load-user-${__VU % 20}`, scene, numItems: requestedItems,
    platform: 'shopify', country: 'SG', region: 'SEA', locale: 'en-SG', currency: 'SGD',
  };
  const headers = { 'Content-Type': 'application/json' };
  if (__ENV.JAVA_INTERNAL_SERVICE_TOKEN) {
    headers['X-Internal-Service-Token'] = __ENV.JAVA_INTERNAL_SERVICE_TOKEN;
  }
  const response = http.post(`${baseUrl}/api/v1/recommend`, JSON.stringify(request), {
    headers, timeout: __ENV.REQUEST_TIMEOUT || '70s', tags: { name: 'recommend', scene },
  });
  let body;
  try { body = response.json(); } catch (_) { body = null; }
  const valid = response.status === 200 && Array.isArray(body?.products)
    && body.products.length > 0 && body.products.length <= requestedItems;
  if (!valid) console.warn(`Recommendation unsuccessful: status=${response.status}, scene=${scene}, iteration=${exec.scenario.iterationInTest}`);
  const invariants = valid && new Set(body.products.map(p => p.productId)).size === body.products.length
    && body.products.every(p => p.productId && p.stock > 0 && p.crossBorderEligible
      && p.currency === request.currency && p.platform === request.platform
      && Array.isArray(p.supportedRegions)
      && p.supportedRegions.some(value => [request.country, request.region].includes(String(value).toUpperCase())));
  success.add(valid);
  rejected.add(response.status === 429);
  // Availability includes rejections/errors; safety examines every returned 200 output.
  // Do not make a tolerated 429 silently impose a stricter 100% availability target.
  if (response.status === 200) safety.add(invariants);
  latency.add(response.timings.duration);
  if (response.status === 200) {
    check(response, {
      'completed recommendation with unique, in-stock, market-compatible products': () => invariants,
    });
  }
}

export function teardown() {
  // An HTTP-only pass must not hide abandoned runs, leaked permits or growing delivery backlog.
  const deadline = Date.now() + recoveryTimeoutSeconds * 1000;
  const headers = {};
  if (__ENV.JAVA_INTERNAL_SERVICE_TOKEN) headers['X-Internal-Service-Token'] = __ENV.JAVA_INTERNAL_SERVICE_TOKEN;
  let healthy = false;
  do {
    const response = http.get(`${baseUrl}/api/v1/metrics`, { headers, timeout: '5s', tags: { name: 'runtime_recovery' } });
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    const guard = body?.runtime_guard;
    const queue = body?.recommendation_persistence;
    healthy = response.status === 200 && guard?.active_runs === 0
      && guard.available_permits === guard.max_concurrent_runs
      && queue?.runningRuns === 0 && queue.runningTasks === 0 && queue.pendingTasks === 0
      && queue.outboxPending === 0 && queue.outboxInFlight === 0 && queue.outboxDeadLetter === 0;
    if (healthy) break;
    sleep(1);
  } while (Date.now() < deadline);
  recovered.add(healthy);
}
