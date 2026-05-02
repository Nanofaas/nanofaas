# function-sdk-java

Spring Boot SDK for Nanofaas function containers.

This module lets a function author write a normal Spring Boot application and mark exactly one
handler bean with `@NanofaasFunction`. The runtime is auto-configured into the same application
context, so the control plane can invoke the function over HTTP without the author wiring the
request pipeline by hand.

## What this SDK solves

The SDK packages the runtime responsibilities that every function container needs:

- discover the handler bean from the Spring context
- read execution metadata from headers or environment variables
- execute the handler with a timeout boundary
- send the result back to the control plane as a callback
- expose `/health` and `/metrics` for probes and scraping

The request lifecycle starts when the control plane calls `POST /invoke` and ends when the runtime
posts `InvocationResult` back to the control plane callback URL.

## Minimal usage

```java
package com.example.functions;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExampleFunctionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleFunctionApplication.class, args);
    }
}

@NanofaasFunction
class HelloHandler implements FunctionHandler {

    @Override
    public Object handle(InvocationRequest request) {
        return "hello from nanofaas";
    }
}
```

The application class starts the Spring container. `@NanofaasFunction` makes the handler discoverable
as a bean so the runtime can resolve it at invoke time.

## Runtime contract

At request time the runtime expects these inputs:

- `X-Execution-Id` header or `EXECUTION_ID` environment variable
- `X-Trace-Id` header or `TRACE_ID` environment variable
- `CALLBACK_URL` environment variable for posting the result
- `FUNCTION_HANDLER` environment variable when more than one handler bean exists

In warm mode, the control plane can provide execution and trace identifiers per request through
headers. In one-shot mode, the same identifiers must already exist in the environment before the
Spring context starts.

The public endpoints exposed by the runtime are:

- `POST /invoke` to execute the active handler
- `GET /health` for runtime checks and watchdogs
- `GET /metrics` for Prometheus scraping

The callback client posts the result to the control plane using the execution identifier as the
authoritative callback key. If callback delivery fails, the runtime still completes the request with
an HTTP response, but the callback attempt is part of the normal request lifecycle.

## Lifecycle boundaries

The runtime state is container-scoped:

- handler discovery happens during Spring bean initialization
- trace and execution IDs live in request-scoped MDC entries
- cold-start metadata is captured on the first request arrival
- callback worker threads shut down with the application context

That means the SDK assumes a Spring Boot container that stays alive for multiple invocations in warm
mode, while still working in one-shot mode when the required environment is injected up front.
