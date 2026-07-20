import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:8080';
const OFFLOADABLE = __ENV.OFFLOADABLE_FUNCTION || 'word-stats-java';
const CONTROL = __ENV.CONTROL_FUNCTION || 'json-transform-java';
const DURATION = __ENV.DURATION || '60s';

const offloadedRequests = new Counter('offloaded_requests');
const control429 = new Counter('control_429');
// k6 only emits per-tag submetrics for tag combinations referenced in a
// threshold, so http_reqs{function:...} never appears in the summary. Count
// successful offloadable responses explicitly instead of relying on submetrics.
const offloadableSuccesses = new Counter('offloadable_requests');

export const options = {
    scenarios: {
        offloadable: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.OFFLOADABLE_RATE || 20),
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 60,
            env: { FUNCTION: OFFLOADABLE, KIND: 'offloadable' },
        },
        control: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.CONTROL_RATE || 20),
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 60,
            env: { FUNCTION: CONTROL, KIND: 'control' },
        },
    },
    thresholds: {
        // the control function is EXPECTED to shed load as 429s
        'http_req_failed{kind:offloadable}': ['rate<0.05'],
    },
};

export default function () {
    const fn = __ENV.FUNCTION;
    const kind = __ENV.KIND;
    const res = http.post(
        `${BASE_URL}/v1/functions/${fn}:invoke`,
        JSON.stringify({ input: { text: 'the quick brown fox', seq: __ITER } }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '30s',
            tags: { function: fn, kind: kind },
        },
    );
    if (kind === 'offloadable' && res.status === 200) {
        offloadableSuccesses.add(1);
    }
    const offloaded = res.headers['X-Nanofaas-Offloaded'] !== undefined
        || res.headers['X-NanoFaaS-Offloaded'] !== undefined;
    if (res.status === 200 && offloaded) {
        offloadedRequests.add(1, { function: fn });
    }
    if (res.status === 429) {
        control429.add(1, { function: fn });
    }
    check(res, {
        'offloadable is 200': (r) => kind !== 'offloadable' || r.status === 200,
        'control is 200 or 429': (r) => kind !== 'control' || r.status === 200 || r.status === 429,
        'control never offloaded': (r) => kind !== 'control' || !offloaded,
    }, { function: fn, kind: kind });
}
