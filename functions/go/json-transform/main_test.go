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
	data, err := os.ReadFile("../../contract-tests/json-transform.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for _, tc := range fixture.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			actual, err := handleJSONTransform(context.Background(), nanofaas.InvocationRequest{Input: tc.Input})
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

func TestHandleJSONTransformCountByGroup(t *testing.T) {
	result, err := handleJSONTransform(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{
			"data": []any{
				map[string]any{"dept": "eng", "salary": 80000},
				map[string]any{"dept": "sales", "salary": 60000},
				map[string]any{"dept": "eng", "salary": 90000},
			},
			"groupBy":   "dept",
			"operation": "count",
		},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	output := result.(map[string]any)
	groups := output["groups"].(map[string]any)
	if groups["eng"] != 2 {
		t.Fatalf("unexpected groups: %+v", groups)
	}
}

func TestHandleJSONTransformAverageByGroup(t *testing.T) {
	result, err := handleJSONTransform(context.Background(), nanofaas.InvocationRequest{
		Input: map[string]any{
			"data": []any{
				map[string]any{"dept": "eng", "salary": 80000},
				map[string]any{"dept": "eng", "salary": 90000},
			},
			"groupBy":    "dept",
			"operation":  "avg",
			"valueField": "salary",
		},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	output := result.(map[string]any)
	groups := output["groups"].(map[string]any)
	if groups["eng"] != 85000.0 {
		t.Fatalf("unexpected groups: %+v", groups)
	}
}
