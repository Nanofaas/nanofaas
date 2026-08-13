package main

import (
	"context"
	"encoding/base64"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func TestHandleQRCodeReturnsBase64PNGEnvelope(t *testing.T) {
	result, err := handleQRCode(context.Background(), nanofaas.InvocationRequest{Input: map[string]any{"text": "https://example.org/invite/abc", "size": float64(256)}})
	if err != nil {
		t.Fatal(err)
	}
	response := result.(nanofaas.HandlerResponse)
	if response.StatusCode != 200 || response.Headers["Content-Type"] != "image/png" || response.Encoding != "base64" {
		t.Fatalf("unexpected response: %#v", response)
	}
	png, err := base64.StdEncoding.DecodeString(response.Output.(string))
	if err != nil || string(png[:8]) != "\x89PNG\r\n\x1a\n" {
		t.Fatalf("not a PNG: %v", err)
	}
}

func TestHandleQRCodeRejectsInvalidInput(t *testing.T) {
	for _, input := range []any{map[string]any{}, map[string]any{"text": ""}, map[string]any{"text": float64(42)}, map[string]any{"text": string(make([]byte, 1025))}, map[string]any{"text": "https://example.org", "size": float64(127)}} {
		result, err := handleQRCode(context.Background(), nanofaas.InvocationRequest{Input: input})
		if err != nil {
			t.Fatal(err)
		}
		response, ok := result.(nanofaas.HandlerResponse)
		if !ok || response.StatusCode != 422 {
			t.Fatalf("got %#v, want 422 envelope", result)
		}
	}
}
