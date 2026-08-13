package main

import (
	"context"
	"log"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func main() {
	runtime := nanofaas.NewRuntime()
	runtime.Register("handler-envelope", handle)
	if err := runtime.Start(context.Background()); err != nil { log.Fatal(err) }
}

func handle(_ context.Context, req nanofaas.InvocationRequest) (any, error) {
	input, _ := req.Input.(map[string]any)
	return map[string]any{"body": stringValue(input["message"]), "header": req.Headers["x-e2e-token"]}, nil
}

func stringValue(value any) string { result, _ := value.(string); return result }
