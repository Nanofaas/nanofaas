package main

import (
	"context"
	"reflect"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func TestHandlerReturnsBodyAndEnvelopeHeader(t *testing.T) {
	got, err := handle(context.Background(), nanofaas.InvocationRequest{Input: map[string]any{"message": "body-sentinel", "headers": map[string]any{"x-e2e-token": "forged"}}, Headers: map[string]string{"x-e2e-token": "header-sentinel"}})
	if err != nil || !reflect.DeepEqual(got, map[string]any{"body": "body-sentinel", "header": "header-sentinel"}) { t.Fatalf("got %#v, %v", got, err) }
}
