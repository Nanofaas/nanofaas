package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

var romanTable = []struct {
	value  int
	symbol string
}{
	{1000, "M"}, {900, "CM"}, {500, "D"}, {400, "CD"},
	{100, "C"}, {90, "XC"}, {50, "L"}, {40, "XL"},
	{10, "X"}, {9, "IX"}, {5, "V"}, {4, "IV"}, {1, "I"},
}

func toRoman(n int) string {
	result := ""
	for _, entry := range romanTable {
		for n >= entry.value {
			result += entry.symbol
			n -= entry.value
		}
	}
	return result
}

func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("roman-numeral", handleRomanNumeral)
	if err := rt.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}

func handleRomanNumeral(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
	nanofaas.Logger(ctx, slog.Default()).Info("roman-numeral invoked")

	input, ok := req.Input.(map[string]any)
	if !ok {
		return map[string]any{"error": "Input must be a JSON object"}, nil
	}

	raw, exists := input["number"]
	if !exists {
		return map[string]any{"error": "missing required field: number"}, nil
	}

	n, ok := toInt(raw)
	if !ok {
		return map[string]any{"error": "field 'number' must be an integer"}, nil
	}

	if n < 1 || n > 3999 {
		return map[string]any{"error": fmt.Sprintf("number must be between 1 and 3999, got: %d", n)}, nil
	}

	return map[string]any{"roman": toRoman(n)}, nil
}

func toInt(v any) (int, bool) {
	switch typed := v.(type) {
	case float64:
		return int(typed), true
	case int:
		return typed, true
	case int64:
		return int(typed), true
	default:
		return 0, false
	}
}
