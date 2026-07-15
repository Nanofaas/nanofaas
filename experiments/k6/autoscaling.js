// k6 load script for autoscaling E2E test
//
// Generates sustained load to trigger InternalScaler scale-up,
// then ramps down so scale-down to zero can be verified.
//
// Usage:
//   k6 run --env NANOFAAS_URL=http://<IP>:30080 k6/autoscaling.js
//   k6 run --env NANOFAAS_URL=http://<IP>:30080 --env FUNCTION_NAME=my-fn k6/autoscaling.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { validateCorpus } from './payload-model.js';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
const FN = __ENV.FUNCTION_NAME || 'word-stats-java';

export const options = {
    stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 20 },
        { duration: '90s', target: 20 },   // sustained peak — 2+ scale-up cycles
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.30'],    // lenient — scaling causes transient errors
    },
};

const CORPUS = validateCorpus(
    JSON.parse(open('../../functions/test-data/word-stats/performance-small.json')),
    'word-stats',
    'small',
);

export default function () {
    const item = CORPUS.cases[Math.floor(Math.random() * CORPUS.cases.length)];
    const payload = JSON.stringify({ input: item.input });

    const res = http.post(`${BASE_URL}/v1/functions/${FN}:invoke`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(0.05);
}
