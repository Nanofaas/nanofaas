package main

import (
	"context"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

var knownValues = []struct {
	n     int
	roman string
}{
	{1, "I"}, {4, "IV"}, {5, "V"}, {9, "IX"}, {10, "X"},
	{14, "XIV"}, {40, "XL"}, {42, "XLII"}, {90, "XC"},
	{400, "CD"}, {900, "CM"}, {1994, "MCMXCIV"},
	{2024, "MMXXIV"}, {3999, "MMMCMXCIX"},
}

func TestToRomanKnownValues(t *testing.T) {
	for _, tc := range knownValues {
		got := toRoman(tc.n)
		if got != tc.roman {
			t.Errorf("toRoman(%d) = %q, want %q", tc.n, got, tc.roman)
		}
	}
}

func TestHandleRomanNumeralValid(t *testing.T) {
	result, err := handleRomanNumeral(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{"number": float64(42)},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	out := result.(map[string]any)
	if out["roman"] != "XLII" {
		t.Errorf("expected XLII, got %v", out["roman"])
	}
}

func TestHandleRomanNumeralMissingField(t *testing.T) {
	result, err := handleRomanNumeral(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	out := result.(map[string]any)
	if out["error"] != "missing required field: number" {
		t.Errorf("unexpected error: %v", out["error"])
	}
}

func TestHandleRomanNumeralOutOfRange(t *testing.T) {
	result, _ := handleRomanNumeral(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{"number": float64(4000)},
	})
	out := result.(map[string]any)
	if _, hasErr := out["error"]; !hasErr {
		t.Error("expected error for out-of-range number")
	}
}
