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
			Name     string `json:"name"`
			Input    any    `json:"input"`
			Expected any    `json:"expected"`
		} `json:"cases"`
	}
	data, err := os.ReadFile("../../contract-tests/word-stats.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, tc := range fixture.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			actual, err := handleWordStats(context.Background(), nanofaas.InvocationRequest{Input: tc.Input})
			if err != nil {
				t.Fatal(err)
			}
			encoded, _ := json.Marshal(actual)
			var normalized any
			_ = json.Unmarshal(encoded, &normalized)
			if !reflect.DeepEqual(normalized, tc.Expected) {
				t.Fatalf("got %#v, want %#v", normalized, tc.Expected)
			}
		})
	}
}

func TestHandleWordStatsBasicAnalysis(t *testing.T) {
	result, err := handleWordStats(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{
			"text": "the quick brown fox jumps over the lazy dog",
		},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	output := result.(map[string]any)
	if output["wordCount"] != 9 {
		t.Fatalf("unexpected word count: %+v", output)
	}
	if output["uniqueWords"] != 8 {
		t.Fatalf("unexpected unique count: %+v", output)
	}
}

func TestHandleWordStatsStringInput(t *testing.T) {
	result, err := handleWordStats(context.Background(), nanofaas.InvocationRequest{Input: "hello world"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	output := result.(map[string]any)
	if output["wordCount"] != 2 {
		t.Fatalf("unexpected output: %+v", output)
	}
}
