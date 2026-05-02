# Go Function SDK Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Go SDK for NanoFaaS function authoring/runtime that matches the Java Spring SDK's capabilities as closely as possible while staying idiomatic to Go.

**Architecture:** Add a standalone Go module under `function-sdk-go/` that embeds an HTTP runtime exposing `/invoke`, `/health`, and `/metrics`, resolves request-scoped execution context from NanoFaaS headers/environment, dispatches to registered handlers, and posts completion callbacks back to the control-plane. Favor conceptual parity with `function-sdk-java/` over framework parity with Spring: handler discovery becomes explicit registration/builders instead of annotations/autoconfiguration.

**Tech Stack:** Go 1.24+, `net/http`, `context`, `log/slog`, Prometheus `client_golang`, standard testing package, `httptest`, Docker multi-stage builds for examples.

---

## Scope and parity target

The implementation should aim for parity with these Java Spring SDK concepts:

- `@NanofaasFunction` + Spring bean discovery -> explicit Go `Register`/`NewRuntime(...).Handle(...)`
- `FunctionContext` -> request-scoped Go context helpers (`ExecutionID`, `TraceID`, logger enrichment)
- `InvokeController` -> `POST /invoke`
- `HealthController` -> `GET /health`
- `MetricsController` -> `GET /metrics`
- `InvocationRuntimeContextResolver` + `TraceLoggingFilter` -> middleware that resolves header/env precedence and injects request context
- `HandlerExecutor` -> timeout-bounded handler invocation
- `ColdStartTracker` -> first-request cold-start markers
- `CallbackClient` + `CallbackDispatcher` -> async callback delivery with bounded queue and retry logic

Non-goals for the first cut:

- Framework-specific adapters (`gin`, `chi`, `fiber`)
- Code generation from OpenAPI
- Full control-plane client SDK
- Feature parity with `function-sdk-java-lite` as a separate module before the Spring-like SDK is stable

Assumptions:

- The Go SDK is a standalone Go module and is not wired into Gradle builds.
- Examples live under `examples/go/`.
- The initial SDK targets HTTP runtime mode and warm execution first; cold mode works through env fallback for `EXECUTION_ID`.

### Task 1: Scaffold the Go module and repository wiring

**Files:**
- Create: `function-sdk-go/go.mod`
- Create: `function-sdk-go/go.sum`
- Create: `function-sdk-go/README.md`
- Create: `function-sdk-go/.gitignore`
- Modify: `README.md`
- Modify: `docs/testing.md`

**Step 1: Write the failing documentation expectation**

Document in the plan branch that the repository does not yet mention a Go SDK.

**Step 2: Create the Go module**

```go
module github.com/miciav/nanofaas/function-sdk-go

go 1.24

require (
	github.com/prometheus/client_golang v1.23.0
)
```

**Step 3: Add the module README with the intended public surface**

Include:

- what the SDK is for
- warm/cold execution model
- minimal example
- supported env vars: `PORT`, `EXECUTION_ID`, `TRACE_ID`, `CALLBACK_URL`, `FUNCTION_HANDLER`

**Step 4: Update top-level docs**

Add one short section to `README.md` and `docs/testing.md` that points to `function-sdk-go/` and `go test ./...`.

**Step 5: Verify the module scaffolding**

Run: `cd function-sdk-go && go mod tidy`
Expected: `go.sum` generated, exit code `0`

**Step 6: Commit**

```bash
git add function-sdk-go/go.mod function-sdk-go/go.sum function-sdk-go/README.md function-sdk-go/.gitignore README.md docs/testing.md
git commit -m "Add Go function SDK module scaffold"
```

### Task 2: Define NanoFaaS runtime contracts and public handler API

**Files:**
- Create: `function-sdk-go/nanofaas/types.go`
- Create: `function-sdk-go/nanofaas/handler.go`
- Create: `function-sdk-go/nanofaas/errors.go`
- Create: `function-sdk-go/nanofaas/types_test.go`

**Step 1: Write the failing test**

```go
package nanofaas_test

import (
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func TestInvocationResultHelpers(t *testing.T) {
	ok := nanofaas.Success(map[string]any{"ok": true})
	if ok.Error != nil || ok.Output == nil {
		t.Fatalf("expected success output without error")
	}

	err := nanofaas.Failure("HANDLER_ERROR", "boom")
	if err.Error == nil || err.Error.Code != "HANDLER_ERROR" {
		t.Fatalf("expected structured error")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestInvocationResultHelpers`
Expected: FAIL with missing package symbols

**Step 3: Write minimal implementation**

Create types mirroring NanoFaaS runtime needs:

```go
type InvocationRequest struct {
	Input    any               `json:"input"`
	Metadata map[string]string `json:"metadata,omitempty"`
}

type ErrorInfo struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type InvocationResult struct {
	Output any        `json:"output,omitempty"`
	Error  *ErrorInfo `json:"error,omitempty"`
}

type Handler func(ctx context.Context, req InvocationRequest) (any, error)
```

Add helpers:

```go
func Success(output any) InvocationResult
func Failure(code, message string) InvocationResult
```

**Step 4: Add runtime error values**

Expose reusable errors such as:

- `ErrExecutionIDMissing`
- `ErrHandlerNotConfigured`
- `ErrHandlerTimeout`

**Step 5: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas`
Expected: PASS

**Step 6: Commit**

```bash
git add function-sdk-go/nanofaas/types.go function-sdk-go/nanofaas/handler.go function-sdk-go/nanofaas/errors.go function-sdk-go/nanofaas/types_test.go
git commit -m "Add Go SDK runtime contract types"
```

### Task 3: Add request-scoped function context and logger enrichment

**Files:**
- Create: `function-sdk-go/nanofaas/context.go`
- Create: `function-sdk-go/nanofaas/context_test.go`

**Step 1: Write the failing test**

```go
func TestContextCarriesExecutionAndTraceIdentifiers(t *testing.T) {
	ctx := nanofaas.WithInvocationContext(context.Background(), "exec-1", "trace-1")

	if got, ok := nanofaas.ExecutionIDFromContext(ctx); !ok || got != "exec-1" {
		t.Fatalf("unexpected execution id: %q %v", got, ok)
	}
	if got, ok := nanofaas.TraceIDFromContext(ctx); !ok || got != "trace-1" {
		t.Fatalf("unexpected trace id: %q %v", got, ok)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestContextCarriesExecutionAndTraceIdentifiers`
Expected: FAIL with undefined functions

**Step 3: Write minimal implementation**

Expose:

```go
func WithInvocationContext(ctx context.Context, executionID, traceID string) context.Context
func ExecutionIDFromContext(ctx context.Context) (string, bool)
func TraceIDFromContext(ctx context.Context) (string, bool)
func Logger(ctx context.Context, base *slog.Logger) *slog.Logger
```

`Logger` should add `execution_id` and `trace_id` fields when present, giving parity with `FunctionContext.getLogger(...)` + MDC behavior.

**Step 4: Add tests for missing values and logger enrichment**

Assert that no fields are added when IDs are absent, and that both IDs are attached when present.

**Step 5: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas`
Expected: PASS

**Step 6: Commit**

```bash
git add function-sdk-go/nanofaas/context.go function-sdk-go/nanofaas/context_test.go
git commit -m "Add Go SDK invocation context helpers"
```

### Task 4: Add runtime settings and header/env resolution

**Files:**
- Create: `function-sdk-go/nanofaas/runtime_settings.go`
- Create: `function-sdk-go/nanofaas/runtime_settings_test.go`

**Step 1: Write the failing test**

```go
func TestResolveInvocationContextPrefersHeadersOverEnvironment(t *testing.T) {
	settings := nanofaas.RuntimeSettings{
		ExecutionID: "env-exec",
		TraceID:     "env-trace",
	}

	got := settings.ResolveInvocationContext("header-exec", "header-trace")

	if got.ExecutionID != "header-exec" || got.TraceID != "header-trace" {
		t.Fatalf("unexpected resolved context: %+v", got)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestResolveInvocationContextPrefersHeadersOverEnvironment`
Expected: FAIL with missing `RuntimeSettings`

**Step 3: Write minimal implementation**

Implement:

```go
type RuntimeSettings struct {
	Port            string
	ExecutionID     string
	TraceID         string
	CallbackURL     string
	FunctionHandler string
	HandlerTimeout  time.Duration
}

type InvocationContext struct {
	ExecutionID string
	TraceID     string
}

func LoadRuntimeSettingsFromEnv() RuntimeSettings
func (s RuntimeSettings) ResolveInvocationContext(headerExecutionID, headerTraceID string) InvocationContext
```

Behavior:

- `X-Execution-Id` overrides `EXECUTION_ID`
- `X-Trace-Id` overrides `TRACE_ID`
- default `Port` is `8080`
- default handler timeout is `30s`

**Step 4: Add table-driven tests**

Cover:

- headers override env
- blank headers fall back to env
- env-only cold mode
- default values

**Step 5: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas`
Expected: PASS

**Step 6: Commit**

```bash
git add function-sdk-go/nanofaas/runtime_settings.go function-sdk-go/nanofaas/runtime_settings_test.go
git commit -m "Add Go SDK runtime settings resolution"
```

### Task 5: Add handler registry and builder-style runtime configuration

**Files:**
- Create: `function-sdk-go/nanofaas/runtime.go`
- Create: `function-sdk-go/nanofaas/runtime_test.go`

**Step 1: Write the failing test**

```go
func TestRuntimeResolvesSingleRegisteredHandler(t *testing.T) {
	rt := nanofaas.NewRuntime()
	rt.Register("echo", func(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
		return req.Input, nil
	})

	handler, err := rt.ResolveHandler()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}

	out, err := handler(context.Background(), nanofaas.InvocationRequest{Input: "hello"})
	if err != nil || out != "hello" {
		t.Fatalf("unexpected output: %v %v", out, err)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestRuntimeResolvesSingleRegisteredHandler`
Expected: FAIL with missing runtime API

**Step 3: Write minimal implementation**

Expose an explicit runtime API instead of annotation scanning:

```go
type Runtime struct { ... }

func NewRuntime(opts ...Option) *Runtime
func (r *Runtime) Register(name string, handler Handler)
func (r *Runtime) WithLogger(logger *slog.Logger) *Runtime
func (r *Runtime) WithSettings(settings RuntimeSettings) *Runtime
func (r *Runtime) ResolveHandler() (Handler, error)
```

Selection behavior must match Java `HandlerRegistry` semantics:

- zero handlers -> error
- one handler -> select it
- multiple handlers + `FUNCTION_HANDLER` configured -> select named handler
- multiple handlers + no configured handler -> error listing names

**Step 4: Add tests for multi-handler resolution**

Assert that the error message names all available handlers.

**Step 5: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas`
Expected: PASS

**Step 6: Commit**

```bash
git add function-sdk-go/nanofaas/runtime.go function-sdk-go/nanofaas/runtime_test.go
git commit -m "Add Go SDK runtime registry and builder"
```

### Task 6: Implement `/invoke` with timeout-bounded execution and cold-start headers

**Files:**
- Create: `function-sdk-go/nanofaas/http_invoke.go`
- Create: `function-sdk-go/nanofaas/cold_start.go`
- Create: `function-sdk-go/nanofaas/http_invoke_test.go`
- Create: `function-sdk-go/nanofaas/cold_start_test.go`

**Step 1: Write the failing test**

```go
func TestInvokeReturnsHandlerOutputAndExecutionHeaders(t *testing.T) {
	rt := nanofaas.NewRuntime(
		nanofaas.WithSettings(nanofaas.RuntimeSettings{ExecutionID: "env-exec"}),
	)
	rt.Register("echo", func(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
		return map[string]any{"echo": req.Input}, nil
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Execution-Id", "exec-1")
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("unexpected status %d", rec.Code)
	}
	if rec.Header().Get("X-Cold-Start") != "true" {
		t.Fatalf("expected cold start header")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestInvokeReturnsHandlerOutputAndExecutionHeaders`
Expected: FAIL with missing HTTP handler

**Step 3: Write minimal implementation**

Implement:

- JSON decoding of `InvocationRequest`
- runtime context resolution from headers/env
- request-scoped context injection
- execution ID validation
- timeout-bounded handler execution using `context.WithTimeout`
- synchronous HTTP response body equal to raw handler output
- `X-Cold-Start: true` and `X-Init-Duration-Ms` on first invocation

Match Java semantics:

- missing execution ID -> `400`
- handler error -> `500` with `{"error":"..."}` body
- timeout -> `504` with `{"error":"Handler timed out"}`

**Step 4: Add callback dispatch hook**

Even for sync HTTP success/error, enqueue callback submission to the control-plane completion endpoint.

**Step 5: Add tests**

Cover:

- header execution ID takes precedence
- env execution ID fallback
- bad JSON -> `400`
- handler error -> `500`
- timeout -> `504`
- cold-start headers only on first request

**Step 6: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas -run 'TestInvoke|TestColdStart'`
Expected: PASS

**Step 7: Commit**

```bash
git add function-sdk-go/nanofaas/http_invoke.go function-sdk-go/nanofaas/cold_start.go function-sdk-go/nanofaas/http_invoke_test.go function-sdk-go/nanofaas/cold_start_test.go
git commit -m "Add Go SDK invoke endpoint and cold start tracking"
```

### Task 7: Implement callback client and bounded async callback dispatcher

**Files:**
- Create: `function-sdk-go/nanofaas/callback_client.go`
- Create: `function-sdk-go/nanofaas/callback_dispatcher.go`
- Create: `function-sdk-go/nanofaas/callback_client_test.go`
- Create: `function-sdk-go/nanofaas/callback_dispatcher_test.go`

**Step 1: Write the failing test**

```go
func TestCallbackClientBuildsExecutionCompleteURL(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/internal/executions/exec-1:complete" && r.URL.Path != "/v1/executions/exec-1:complete" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	client := nanofaas.NewCallbackClient(server.URL + "/v1/executions")
	ok := client.SendResult(context.Background(), "exec-1", nanofaas.Success("ok"), "trace-1")
	if !ok {
		t.Fatalf("expected callback to succeed")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestCallbackClientBuildsExecutionCompleteURL`
Expected: FAIL with missing callback client

**Step 3: Write minimal implementation**

Mirror Java `CallbackClient` behavior:

- skip when `CALLBACK_URL` is blank
- skip when `executionId` is blank
- retry `3` times with delays `100ms`, `500ms`, `2s`
- do not retry permanent `4xx` except `408` and `429`
- strip trailing slash
- if base URL already ends with `:complete`, remove existing suffix before appending authoritative execution ID

**Step 4: Write dispatcher implementation**

Expose:

```go
type CallbackDispatcher struct { ... }

func NewCallbackDispatcher(client *CallbackClient, workerCount, queueSize int) *CallbackDispatcher
func (d *CallbackDispatcher) Submit(ctx context.Context, executionID string, result InvocationResult, traceID string) bool
func (d *CallbackDispatcher) Shutdown(ctx context.Context) error
```

Behavior:

- bounded queue
- drops when full
- background worker goroutines
- graceful shutdown

**Step 5: Add tests**

Cover:

- success path
- blank URL / blank execution ID
- temporary retry then success
- permanent 4xx no retry
- queue full returns `false`

**Step 6: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas -run 'TestCallback|TestDispatcher'`
Expected: PASS

**Step 7: Commit**

```bash
git add function-sdk-go/nanofaas/callback_client.go function-sdk-go/nanofaas/callback_dispatcher.go function-sdk-go/nanofaas/callback_client_test.go function-sdk-go/nanofaas/callback_dispatcher_test.go
git commit -m "Add Go SDK callback delivery"
```

### Task 8: Add `/health`, `/metrics`, and runtime server lifecycle

**Files:**
- Create: `function-sdk-go/nanofaas/http_health.go`
- Create: `function-sdk-go/nanofaas/http_metrics.go`
- Create: `function-sdk-go/nanofaas/server.go`
- Create: `function-sdk-go/nanofaas/server_test.go`

**Step 1: Write the failing test**

```go
func TestRuntimeExposesHealthAndMetricsEndpoints(t *testing.T) {
	rt := nanofaas.NewRuntime()
	srv := httptest.NewServer(rt.Handler())
	defer srv.Close()

	healthResp, err := http.Get(srv.URL + "/health")
	if err != nil || healthResp.StatusCode != http.StatusOK {
		t.Fatalf("health failed: %v", err)
	}

	metricsResp, err := http.Get(srv.URL + "/metrics")
	if err != nil || metricsResp.StatusCode != http.StatusOK {
		t.Fatalf("metrics failed: %v", err)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd function-sdk-go && go test ./nanofaas -run TestRuntimeExposesHealthAndMetricsEndpoints`
Expected: FAIL with missing routes

**Step 3: Write minimal implementation**

Expose:

- `GET /health` -> `{"status":"ok"}`
- `GET /metrics` -> Prometheus scrape output
- `func (r *Runtime) Handler() http.Handler`
- `func (r *Runtime) Start(ctx context.Context) error`

`Start` should:

- default to `:8080`
- use `http.Server`
- shut down callback dispatcher on server stop

**Step 4: Add base runtime metrics**

Track at least:

- invocation count by status
- handler duration
- callback enqueue drops
- cold-start count

**Step 5: Run tests**

Run: `cd function-sdk-go && go test ./nanofaas`
Expected: PASS

**Step 6: Commit**

```bash
git add function-sdk-go/nanofaas/http_health.go function-sdk-go/nanofaas/http_metrics.go function-sdk-go/nanofaas/server.go function-sdk-go/nanofaas/server_test.go
git commit -m "Add Go SDK health metrics and server lifecycle"
```

### Task 9: Add executable examples mirroring the Java SDK examples

**Files:**
- Create: `examples/go/word-stats/go.mod`
- Create: `examples/go/word-stats/main.go`
- Create: `examples/go/word-stats/Dockerfile`
- Create: `examples/go/json-transform/go.mod`
- Create: `examples/go/json-transform/main.go`
- Create: `examples/go/json-transform/Dockerfile`
- Modify: `docs/example-function.md`
- Modify: `README.md`

**Step 1: Write the failing example smoke test**

Create package-level example tests or a shell-validated run command in the README section.

Minimal desired example:

```go
func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("word-stats", handleWordStats)
	if err := rt.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}
```

**Step 2: Run a compile-only check**

Run: `cd examples/go/word-stats && go test ./...`
Expected: FAIL until module and imports exist

**Step 3: Implement examples**

Port the business logic from:

- `examples/java/word-stats/.../WordStatsHandler.java`
- `examples/java/json-transform/.../JsonTransformHandler.java`

Keep request/response shapes identical so the existing E2E assertions can be reused.

**Step 4: Add Dockerfiles**

Use multi-stage builds:

- build static or mostly static binary
- run on distroless or alpine
- expose `8080`

**Step 5: Update docs**

Document how to run the Go examples locally and how to register them as `POOL`/HTTP functions.

**Step 6: Verify**

Run:

- `cd examples/go/word-stats && go test ./...`
- `cd examples/go/json-transform && go test ./...`

Expected: PASS

**Step 7: Commit**

```bash
git add examples/go/word-stats examples/go/json-transform docs/example-function.md README.md
git commit -m "Add Go SDK example functions"
```

### Task 10: Add end-to-end coverage against the real control-plane

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/SdkExamplesE2eTest.java`
- Modify: `docs/testing.md`
- Optional create: `scripts/e2e-go-sdk.sh`

**Step 1: Write the failing E2E assertion**

Add a new scenario that starts the Go examples as containers and registers them as pool functions.

Follow the shape already used in `SdkExamplesE2eTest`:

```java
private static final GenericContainer<?> wordStatsGo = new GenericContainer<>(...);
private static final GenericContainer<?> jsonTransformGo = new GenericContainer<>(...);
```

**Step 2: Run the targeted E2E class**

Run: `./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.SdkExamplesE2eTest -PrunE2e=true`
Expected: FAIL because Go example containers are not present yet

**Step 3: Implement the E2E wiring**

Add:

- Go containers
- function registration payloads using `endpointUrl`
- assertions reusing the same payloads/outcomes as Java SDK examples

**Step 4: Add a documentation shortcut**

If `scripts/e2e-go-sdk.sh` is added, it should invoke the targeted Gradle test and explain prerequisites.

**Step 5: Run verification**

Run:

- `cd function-sdk-go && go test ./...`
- `./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.SdkExamplesE2eTest -PrunE2e=true`

Expected: PASS

**Step 6: Commit**

```bash
git add control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/SdkExamplesE2eTest.java docs/testing.md scripts/e2e-go-sdk.sh
git commit -m "Add Go SDK end-to-end coverage"
```

## Notes for the implementing engineer

- Keep the Go SDK public API small. The main experience should be:

```go
rt := nanofaas.NewRuntime()
rt.Register("echo", func(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
	logger := nanofaas.Logger(ctx, slog.Default())
	logger.Info("handling request")
	return map[string]any{"echo": req.Input}, nil
})
return rt.Start(context.Background())
```

- Resist adding framework adapters in the first milestone. The Java Spring SDK is "advanced" because of runtime features, not because of Spring itself.
- Mirror Java behavior where it affects interoperability:
  - header/env precedence
  - callback URL normalization
  - bounded async callback dispatch
  - cold-start headers
  - `/health` and `/metrics`
- Keep output payloads as `any` / JSON-compatible values, matching the Java handlers and control-plane expectations.
- Prefer table-driven tests and `httptest` for fast SDK tests; reserve Docker/Testcontainers only for control-plane E2E.

## Verification checklist

- `cd function-sdk-go && go test ./...`
- `cd examples/go/word-stats && go test ./...`
- `cd examples/go/json-transform && go test ./...`
- `./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.SdkExamplesE2eTest -PrunE2e=true`
- Manual smoke test:

```bash
cd examples/go/word-stats
go run .
curl -s localhost:8080/health
curl -s -X POST localhost:8080/invoke \
  -H 'Content-Type: application/json' \
  -H 'X-Execution-Id: exec-123' \
  -d '{"input":{"text":"hello hello world","topN":2}}'
```
