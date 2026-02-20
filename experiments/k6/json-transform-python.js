import http from 'k6/http';
import { sleep } from 'k6';
import { buildInvocationPayload, buildJsonTransformInput, checkInvocationResponse, invocationPath, selectPayloadIndex } from './common.js';

const FN = 'json-transform-python';

export const options = {
    stages: [
        { duration: '10s', target: 5 },
        { duration: '30s', target: 10 },
        { duration: '30s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],
        http_req_failed: ['rate<0.15'],
    },
};

export default function () {
    const payload = buildInvocationPayload(buildJsonTransformInput(selectPayloadIndex()));

    const res = http.post(invocationPath(FN), payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    checkInvocationResponse(res, (body) => {
        return body.output && body.output.groups !== undefined;
    });

    sleep(0.1);
}
