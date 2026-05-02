import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:8080';
const FUNCTION_NAME = __ENV.NANOFAAS_FUNCTION || 'tool-metrics-echo';

export const options = {
    stages: [
        { duration: '10s', target: 5 },
        { duration: '20s', target: 10 },
        { duration: '20s', target: 20 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],
        http_req_failed: ['rate<0.15'],
    },
};

export default function () {
    const payload = JSON.stringify({
        input: { seq: __ITER, source: 'controlplane-tool' },
        metadata: { source: 'controlplane-tool' },
    });
    const url = `${BASE_URL}/v1/functions/${FUNCTION_NAME}:invoke`;
    const res = http.post(url, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '20s',
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has success response': (r) => {
            if (r.status !== 200) {
                return false;
            }
            try {
                const body = JSON.parse(r.body);
                return body && body.status === 'success';
            } catch (e) {
                return false;
            }
        },
    });

    sleep(0.1);
}
