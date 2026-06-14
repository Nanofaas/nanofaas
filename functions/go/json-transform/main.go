package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"strings"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("json-transform", handleJSONTransform)
	if err := rt.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}

func handleJSONTransform(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
	nanofaas.Logger(ctx, slog.Default()).Info("processing json transform")

	input, ok := req.Input.(map[string]any)
	if !ok {
		return map[string]any{"error": "Input must be a JSON object"}, nil
	}

	data, ok := input["data"].([]any)
	groupBy, groupByOK := input["groupBy"].(string)
	operation, _ := input["operation"].(string)
	if operation == "" {
		operation = "count"
	}
	valueField, _ := input["valueField"].(string)

	if !ok || !groupByOK {
		return map[string]any{"error": "Fields 'data' (array) and 'groupBy' (string) are required"}, nil
	}
	if strings.ToLower(operation) != "count" && valueField == "" {
		return map[string]any{"error": "Field 'valueField' is required for operation: " + operation}, nil
	}

	groups := map[string][]map[string]any{}
	order := make([]string, 0, len(data))
	for _, raw := range data {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		key := "null"
		if value, found := item[groupBy]; found && value != nil {
			key = toString(value)
		}
		if _, seen := groups[key]; !seen {
			order = append(order, key)
		}
		groups[key] = append(groups[key], item)
	}

	resultGroups := map[string]any{}
	for _, key := range order {
		items := groups[key]
		switch strings.ToLower(operation) {
		case "count":
			resultGroups[key] = len(items)
		case "sum":
			resultGroups[key] = aggregate(items, valueField, sum)
		case "avg":
			resultGroups[key] = aggregate(items, valueField, avg)
		case "min":
			resultGroups[key] = aggregate(items, valueField, min)
		case "max":
			resultGroups[key] = aggregate(items, valueField, max)
		default:
			resultGroups[key] = "unknown operation: " + operation
		}
	}

	return map[string]any{
		"groupBy":   groupBy,
		"operation": operation,
		"groups":    resultGroups,
	}, nil
}

type aggregation func([]float64) float64

func aggregate(items []map[string]any, field string, op aggregation) float64 {
	values := make([]float64, 0, len(items))
	for _, item := range items {
		if value, ok := number(item[field]); ok {
			values = append(values, value)
		}
	}
	if len(values) == 0 {
		return 0
	}
	return op(values)
}

func sum(values []float64) float64 {
	total := 0.0
	for _, value := range values {
		total += value
	}
	return total
}

func avg(values []float64) float64 {
	return sum(values) / float64(len(values))
}

func min(values []float64) float64 {
	current := values[0]
	for _, value := range values[1:] {
		if value < current {
			current = value
		}
	}
	return current
}

func max(values []float64) float64 {
	current := values[0]
	for _, value := range values[1:] {
		if value > current {
			current = value
		}
	}
	return current
}

func number(value any) (float64, bool) {
	switch typed := value.(type) {
	case int:
		return float64(typed), true
	case int64:
		return float64(typed), true
	case float64:
		return typed, true
	default:
		return 0, false
	}
}

func toString(value any) string {
	switch typed := value.(type) {
	case string:
		return typed
	default:
		return fmt.Sprint(value)
	}
}
