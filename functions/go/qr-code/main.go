package main

import (
	"context"
	"encoding/base64"
	"log"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
	"github.com/skip2/go-qrcode"
)

func main() {
	runtime := nanofaas.NewRuntime()
	runtime.Register("qr-code", handleQRCode)
	if err := runtime.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}

func handleQRCode(_ context.Context, request nanofaas.InvocationRequest) (any, error) {
	input, ok := request.Input.(map[string]any)
	if !ok {
		return errorResponse("Input must be a JSON object"), nil
	}
	rawText, exists := input["text"]
	if !exists {
		return errorResponse("missing required field: text"), nil
	}
	text, ok := rawText.(string)
	if !ok || text == "" {
		return errorResponse("field 'text' must be a non-empty string"), nil
	}
	if len([]byte(text)) > 1024 {
		return errorResponse("field 'text' must be at most 1024 UTF-8 bytes"), nil
	}
	size := 256
	if rawSize, exists := input["size"]; exists {
		value, ok := rawSize.(float64)
		if !ok || value != float64(int(value)) || value < 128 || value > 1024 {
			return errorResponse("field 'size' must be an integer between 128 and 1024"), nil
		}
		size = int(value)
	}
	png, err := qrcode.Encode(text, qrcode.Medium, size)
	if err != nil {
		return nil, err
	}
	return nanofaas.HandlerResponse{Output: base64.StdEncoding.EncodeToString(png), StatusCode: 200, Headers: map[string]string{"Content-Type": "image/png"}, Encoding: "base64"}, nil
}

func errorResponse(message string) nanofaas.HandlerResponse {
	return nanofaas.NewHandlerResponse(map[string]any{"error": message}, 422)
}
