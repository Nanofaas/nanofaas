package main

import (
	"context"
	"testing"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

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
