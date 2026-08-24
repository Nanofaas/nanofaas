import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// The traffic the platform actually receives: both doors, and a few requests that
// carry an idempotency key.
//
// Every load test so far has been 100% synchronous, which is the one mix that cannot
// answer the question this run exists for. With sync-queue off and async-queue on -
// the configuration every comparison uses - ReactiveInvocationCoordinator.admitLocally
// and InvocationService.invokeAsync make the identical enqueue call, so both kinds of
// work land in ONE FunctionQueueState: one bounded queue of 20, one inFlight counter,
// one pair of concurrency slots. Sync traffic alone already has 14.9-15.0% of its
// arrivals refused at 2x, measured on the runs that carry the declared 5,000-VU pool
// and drop nothing at the generator. What a share of async does to that budget is what
// this measures.
//
// The SHAPE is copied from runtime-comparison.js on purpose, stage for stage, so the
// mixed numbers can be put beside the six pure-sync runs already recorded rather than
// beside nothing.
const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
const FN_JAVA = __ENV.NANOFAAS_FUNCTION || 'word-stats-java';
const FN_JS = __ENV.NANOFAAS_NEIGHBOUR || 'word-stats-javascript';

const WARM_RPS = Number(__ENV.K6_WARM_RPS || 40);
const BASE_RPS = Number(__ENV.K6_BASE_RPS || 200);
const HIGH_RPS = Number(__ENV.K6_HIGH_RPS || 350);
const SPIKE_RPS = Number(__ENV.K6_SPIKE_RPS || 600);
const PEAK_RPS = Number(__ENV.K6_PEAK_RPS || 900);
const RATE_SCALE = Number(__ENV.K6_RATE_SCALE || 1);

// The mix. Deliberately a minority of async and a small minority of keys: a 50/50
// split would answer a question nobody has ("what if half the traffic changed kind"),
// while the interesting one is whether a minority can displace the majority.
const ASYNC_SHARE = Number(__ENV.K6_ASYNC_SHARE ?? 0.2);
const IDEM_SHARE = Number(__ENV.K6_IDEM_SHARE ?? 0.05);

// Both at zero make this the purely synchronous generator, which is how the
// comparison arms of a matrix are driven: one script, the mix as parameters, so
// every arm is measured by the same code and runtime-comparison.js stays untouched.
const MANAGEMENT_URL = __ENV.NANOFAAS_MANAGEMENT_URL || BASE_URL.replace(/:\d+$/, ':30081');
const PROBE_RPS = Number(__ENV.K6_PROBE_RPS ?? 1);

// An idempotent iteration issues its request TWICE with the same key, so it holds a VU
// for two round trips. The pool is sized for that rather than for the nominal rate.
const VU_LATENCY_BUDGET_S = Number(__ENV.K6_VU_LATENCY_BUDGET_S || 0.5);
const IDEM_COST = 1 + IDEM_SHARE;
const MAX_VUS = Number(
    __ENV.K6_MAX_VUS
        || Math.max(600, Math.ceil(PEAK_RPS * RATE_SCALE * VU_LATENCY_BUDGET_S * IDEM_COST)),
);

const JS_SCALE = Number(__ENV.K6_JS_SCALE || 0.35);

const SHAPE = [
    ['30s', WARM_RPS],
    ['60s', BASE_RPS],
    ['60s', BASE_RPS],
    ['10s', SPIKE_RPS],
    ['30s', SPIKE_RPS],
    ['10s', BASE_RPS],
    ['60s', BASE_RPS],
    ['60s', HIGH_RPS],
    ['45s', HIGH_RPS],
    ['10s', PEAK_RPS],
    ['30s', PEAK_RPS],
    ['45s', WARM_RPS],
];

const stages = (scale) =>
    SHAPE.map(([duration, target]) => ({
        duration,
        target: Math.max(1, Math.round(target * scale)),
    }));

const arrivals = (scale, exec) => ({
    executor: 'ramping-arrival-rate',
    startRate: Math.max(1, Math.round(WARM_RPS * scale)),
    timeUnit: '1s',
    preAllocatedVUs: MAX_VUS,
    maxVUs: MAX_VUS,
    stages: stages(scale),
    exec,
});

export const options = {
    scenarios: {
        java: arrivals(RATE_SCALE, 'java'),
        javascript: arrivals(JS_SCALE * RATE_SCALE, 'javascript'),
        // One request a second against 2,700: the load it adds is not measurable,
        // and what it buys is the only direct reading of the quantity that decides
        // whether the run survives at all.
        probe: {
            executor: 'constant-arrival-rate',
            rate: PROBE_RPS,
            timeUnit: '1s',
            duration: SHAPE.reduce((total, [d]) => total + parseInt(d, 10), 0) + 's',
            preAllocatedVUs: 4,
            maxVUs: 16,
            exec: 'probe',
        },
    },
    thresholds: {},
};

// k6 does not break http_req_duration down by tag in the summary, and a mixed run read
// as one aggregate is exactly the mixture this script exists to take apart. So each
// door keeps its own series. The async one measures the ACK, not the completion —
// nobody is waiting on the other side, and pretending otherwise would put a number in
// the table that means nothing.
const syncDuration = new Trend('mixed_sync_duration', true);
const syncIdemDuration = new Trend('mixed_sync_idem_duration', true);
const asyncAckDuration = new Trend('mixed_async_ack_duration', true);
const syncRefused = new Rate('mixed_sync_refused');
const asyncRefused = new Rate('mixed_async_refused');
const idemAgreed = new Rate('mixed_idem_same_execution');
const idemPairs = new Counter('mixed_idem_pairs');

// What kubelet measures, measured the way kubelet measures it.
//
// On 2026-08-23 the liveness probe stopped answering within its second at 3x, on
// synchronous and mixed traffic alike, and kubelet killed a control plane that was
// serving requests - taking the whole in-memory registry with it. Everything known
// about that failure was inferred: 863 pending tasks per event loop, 68.9% of CFS
// periods throttled, an event line after the fact. None of those is the probe's
// response time; they are its presumed causes.
//
// timeoutSeconds is 1 and failureThreshold is 3, so mixed_probe_over_budget counts
// the samples that would have cost a strike.
const probeDuration = new Trend('mixed_probe_duration', true);
const probeOverBudget = new Rate('mixed_probe_over_budget');
const PROBE_BUDGET_MS = Number(__ENV.K6_PROBE_BUDGET_MS || 1000);

export function probe() {
    const response = http.get(`${MANAGEMENT_URL}/actuator/health/liveness`, {
        timeout: '5s',
        tags: { fn: 'management', path: 'probe' },
    });
    probeDuration.add(response.timings.duration);
    probeOverBudget.add(response.timings.duration > PROBE_BUDGET_MS || response.status !== 200);
}

const TEXTS = [
    'The quick brown fox jumps over the lazy dog. The dog barked at the fox while the fox ran away quickly.',
    'Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.',
    'To be or not to be that is the question whether tis nobler in the mind to suffer the slings and arrows of outrageous fortune.',
    'It was the best of times it was the worst of times it was the age of wisdom it was the age of foolishness.',
];

function body() {
    return JSON.stringify({ input: { text: TEXTS[__ITER % TEXTS.length], topN: 5 } });
}

function post(url, payload, extraHeaders, tags) {
    return http.post(url, payload, {
        headers: Object.assign({ 'Content-Type': 'application/json' }, extraHeaders || {}),
        timeout: '30s',
        tags,
    });
}

// A key is not a load-shaping trick: it is a client that did not get an answer and is
// asking again. So the pair is sent as a retry would be - same key, same payload - and
// what is checked is the platform's actual promise: one execution, not two.
function idempotent(functionName) {
    const key = `k6-${__VU}-${__ITER}`;
    const payload = body();
    const headers = { 'Idempotency-Key': key };
    const tags = { fn: functionName, path: 'sync', idem: 'yes' };
    const url = `${BASE_URL}/v1/functions/${functionName}:invoke`;

    const first = post(url, payload, headers, tags);
    const second = post(url, payload, headers, tags);
    syncIdemDuration.add(first.timings.duration);
    syncIdemDuration.add(second.timings.duration);
    syncRefused.add(first.status === 429 || first.status === 503);
    syncRefused.add(second.status === 429 || second.status === 503);

    // Only meaningful when both were served: a refusal never reached the store, so it
    // has no execution id to agree about, and counting it would dilute the one check
    // that says idempotency held.
    const a = first.headers['X-Execution-Id'];
    const b = second.headers['X-Execution-Id'];
    if (first.status === 200 && second.status === 200) {
        idemPairs.add(1);
        idemAgreed.add(a !== undefined && a === b);
        check(first, { 'idempotent pair shares one execution': () => a !== undefined && a === b });
    }
}

function synchronous(functionName) {
    const response = post(
        `${BASE_URL}/v1/functions/${functionName}:invoke`,
        body(),
        null,
        { fn: functionName, path: 'sync', idem: 'no' },
    );
    syncDuration.add(response.timings.duration);
    syncRefused.add(response.status === 429 || response.status === 503);
    check(response, { 'sync answered': (r) => r.status === 200 || r.status === 429 || r.status === 503 });
}

function asynchronous(functionName) {
    const response = post(
        `${BASE_URL}/v1/functions/${functionName}:enqueue`,
        body(),
        null,
        { fn: functionName, path: 'async', idem: 'no' },
    );
    asyncAckDuration.add(response.timings.duration);
    asyncRefused.add(response.status === 429 || response.status === 503);
    // 202 is the success here. A 501 would mean async-queue is not loaded, which is a
    // misconfigured run rather than a result, so it is checked apart from the refusals.
    check(response, { 'async accepted or refused': (r) => r.status === 202 || r.status === 429 || r.status === 503 });
}

function drive(functionName) {
    const draw = Math.random();
    if (draw < ASYNC_SHARE) {
        asynchronous(functionName);
    } else if (draw < ASYNC_SHARE + IDEM_SHARE) {
        idempotent(functionName);
    } else {
        synchronous(functionName);
    }
}

export function java() {
    drive(FN_JAVA);
}

export function javascript() {
    drive(FN_JS);
}
