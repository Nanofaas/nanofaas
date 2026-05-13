import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:8080';
const FUNCTION_NAME = __ENV.NANOFAAS_FUNCTION || 'word-stats-java';
const PAYLOAD_PATH = __ENV.NANOFAAS_PAYLOAD || '';
const PAYLOAD_BODY = PAYLOAD_PATH ? open(PAYLOAD_PATH) : '';

function requestPayload() {
    if (PAYLOAD_BODY) {
        return PAYLOAD_BODY;
    }
    return JSON.stringify({
        input: { text: 'the quick brown fox jumps over the lazy dog', seq: __ITER },
        metadata: { source: 'two-vm-loadtest' },
    });
}

export const options = {
    thresholds: {
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],
        http_req_failed: ['rate<0.15'],
    },
};

export default function () {
    const url = `${BASE_URL}/v1/functions/${FUNCTION_NAME}:invoke`;
    const res = http.post(url, requestPayload(), {
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
