package main

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func TestSharedContract(t *testing.T) {
	var fixture struct {
		Cases []struct {
			Name               string `json:"name"`
			Input              any    `json:"input"`
			Expected           any    `json:"expected"`
			ExpectedStatusCode *int   `json:"expectedStatusCode"`
		} `json:"cases"`
	}
	data, err := os.ReadFile("../../test-data/roman-numeral/correctness.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, tc := range fixture.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			actual, err := handleRomanNumeral(context.Background(), nanofaas.InvocationRequest{Input: tc.Input})
			if err != nil {
				t.Fatal(err)
			}
			assertContractCase(t, actual, tc.ExpectedStatusCode, tc.Expected)
		})
	}
}

func assertContractCase(t *testing.T, actual any, expectedStatusCode *int, expected any) {
	if expectedStatusCode != nil {
		response, ok := actual.(nanofaas.HandlerResponse)
		if !ok || response.StatusCode != *expectedStatusCode {
			t.Fatalf("got %#v, want HandlerResponse status %d", actual, *expectedStatusCode)
		}
		actual = response.Output
	}
	encoded, _ := json.Marshal(actual)
	var normalized any
	_ = json.Unmarshal(encoded, &normalized)
	if !reflect.DeepEqual(normalized, expected) {
		t.Fatalf("got %#v, want %#v", normalized, expected)
	}
}

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
	response := result.(nanofaas.HandlerResponse)
	if response.StatusCode != 422 {
		t.Fatalf("got status %d, want 422", response.StatusCode)
	}
	out := response.Output.(map[string]any)
	if out["error"] != "missing required field: number" {
		t.Errorf("unexpected error: %v", out["error"])
	}
}

func TestHandleRomanNumeralOutOfRange(t *testing.T) {
	result, _ := handleRomanNumeral(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{"number": float64(4000)},
	})
	response := result.(nanofaas.HandlerResponse)
	if response.StatusCode != 422 {
		t.Fatalf("got status %d, want 422", response.StatusCode)
	}
	out := response.Output.(map[string]any)
	if _, hasErr := out["error"]; !hasErr {
		t.Error("expected error for out-of-range number")
	}
}
