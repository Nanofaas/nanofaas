package main

import (
	"context"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

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
