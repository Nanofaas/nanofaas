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
Timed-out handler work therefore continues to own its handler slot and retained input until the goroutine physically returns. Invoke admission is fail-fast; health and metrics remain independent of invoke saturation.

## Environment variables

- `PORT`: HTTP listen port. Default `8080`.
- `EXECUTION_ID`: fallback execution identifier for cold mode.
- `TRACE_ID`: fallback trace identifier for cold mode.
- `CALLBACK_URL`: control-plane callback base URL used to report completion.
- `FUNCTION_HANDLER`: optional handler name to select when multiple handlers are registered.
- `NANOFAAS_HANDLER_TIMEOUT`: handler wait timeout in milliseconds (or a Go duration). Default `30000`.
- `NANOFAAS_MAX_CONCURRENT_HANDLERS`: physical handler-work cap. Default `32`.
- `NANOFAAS_MAX_INPUT_BYTES`: maximum request body. Default `1048576`.
- `NANOFAAS_MAX_OUTPUT_BYTES`: maximum serialized response body. Default `1048576`.
- `NANOFAAS_MAX_PENDING_CALLBACKS`: combined active and queued callback cap. Default `128`.
- `NANOFAAS_MAX_PENDING_CALLBACK_BYTES`: aggregate callback byte reservation cap. Default `16777216`.
- `NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES`: single serialized callback cap. Default `2097152`.
- `NANOFAAS_BODY_READ_TIMEOUT`: request-body read timeout in milliseconds (or a Go duration). Default `5000`.
- `NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT`: per-attempt callback timeout in milliseconds (or a Go duration). Default `5000`.
- `NANOFAAS_CALLBACK_MAX_ATTEMPTS`: callback delivery attempt cap. Default `3`.
- `NANOFAAS_SHUTDOWN_TIMEOUT`: total stop/drain bound in milliseconds (or a Go duration). Default `5000`.

The runtime reserves one full single-callback allowance before starting a handler. Consequently, effective callback concurrency is bounded by both `NANOFAAS_MAX_PENDING_CALLBACKS` and `floor(NANOFAAS_MAX_PENDING_CALLBACK_BYTES / NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES)`.

Values above production maxima fall back to their defaults before any allocation or timer is created. Maxima are 4,096 concurrent handlers, 65,536 pending callbacks, 10 callback attempts, 64 MiB per input/output/callback payload, 1 GiB aggregate callback reservations, one hour per handler, and five minutes for body reads, callback attempts, and shutdown.

## Bounded JSON output contract

Before calling `encoding/json`, the runtime traverses handler and callback values without creating a serialized copy. It rejects values whose conservative encoded upper bound exceeds the configured limit, cycles, excessive nesting, and unsupported encoders. Ordinary JSON-compatible Go values remain supported, including pointers, structs, maps with built-in keys, slices, arrays, `json.Number`, and `json.RawMessage`.

The runtime explicitly permits standard-library marshalers whose allocation bound is known and checked: `time.Time`, valid `net.IP`, and `net/netip` address, address-port, and prefix values. Nil pointers to these types remain JSON `null`. Invalid values still produce `OUTPUT_SERIALIZATION_ERROR`.

Migration note: arbitrary user-defined `json.Marshaler` and `encoding.TextMarshaler` implementations are rejected with `OUTPUT_SERIALIZATION_ERROR`, including value or pointer receiver methods on named nil slices/maps and addressable struct fields. Their methods return a newly allocated `[]byte`, so the runtime cannot enforce a pre-allocation size bound. Convert such outputs to ordinary bounded values or `json.RawMessage` produced under an application-owned limit. Variable-output standard types such as `math/big` and `regexp.Regexp` are not allowlisted.

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
