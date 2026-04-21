import { AsyncLocalStorage } from "node:async_hooks";

type RequestContext = {
    executionId: string;
    traceId?: string;
};

const requestContext = new AsyncLocalStorage<RequestContext>();

export function runWithContext<T>(context: RequestContext, fn: () => T): T {
    return requestContext.run(context, fn);
}

export function getExecutionId(): string | undefined {
    return requestContext.getStore()?.executionId;
}

export function getTraceId(): string | undefined {
    return requestContext.getStore()?.traceId;
}
