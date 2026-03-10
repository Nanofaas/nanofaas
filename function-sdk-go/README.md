# nanofaas Go Function SDK

Go SDK for authoring NanoFaaS functions with a warm-container HTTP runtime that mirrors the core behavior of the Java Spring SDK.

## Scope

- Persistent HTTP runtime for warm execution mode
- Cold-mode compatibility through environment fallback
- Request-scoped execution and trace context
- Built-in `/invoke`, `/health`, and `/metrics` endpoints
- Callback delivery to the NanoFaaS control-plane

## Runtime model

In warm mode the function process stays alive and serves repeated requests on HTTP.
In cold mode the runtime can still resolve `EXECUTION_ID` and `TRACE_ID` from the environment when per-request headers are not present.
Handlers are expected to respect `context.Context` cancellation promptly. The runtime enforces request deadlines, but Go cannot forcibly terminate a handler goroutine that ignores cancellation.

## Environment variables

- `PORT`: HTTP listen port. Default `8080`.
- `EXECUTION_ID`: fallback execution identifier for cold mode.
- `TRACE_ID`: fallback trace identifier for cold mode.
- `CALLBACK_URL`: control-plane callback base URL used to report completion.
- `FUNCTION_HANDLER`: optional handler name to select when multiple handlers are registered.

## Planned public API

```go
package main

import (
	"context"
	"log/slog"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("echo", func(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
		nanofaas.Logger(ctx, slog.Default()).Info("handling request")
		return map[string]any{"echo": req.Input}, nil
	})

	if err := rt.Start(context.Background()); err != nil {
		panic(err)
	}
}
```

## Development

```bash
cd function-sdk-go
go mod tidy
go test ./...
```
