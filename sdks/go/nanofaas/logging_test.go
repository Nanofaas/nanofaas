package nanofaas

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"strings"
	"sync"
	"testing"
)

func TestSharedLoggingCorpus(t *testing.T) {
	t.Setenv("NANOFAAS_LOG_FORMAT", "json")
	data, err := os.ReadFile("../../runtime-contract/logging-corpus.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct{ Input, Expected map[string]any }
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for _, item := range corpus.Cases {
		var output bytes.Buffer
		execution, _ := item.Input["executionId"].(string)
		trace, _ := item.Input["traceId"].(string)
		ctx := WithInvocationContext(context.Background(), execution, trace)
		logger := Logger(ctx, NewLogger(&output, "function-runtime"))
		args := []any{}
		if attributes, ok := item.Input["attributes"].(map[string]any); ok {
			for key, value := range attributes {
				args = append(args, key, value)
			}
		}
		level := slog.LevelInfo
		if item.Input["level"] == "WARN" {
			level = slog.LevelWarn
		}
		if item.Input["level"] == "ERROR" {
			level = slog.LevelError
		}
		logger.Log(ctx, level, item.Input["message"].(string), args...)
		var event map[string]any
		if err := json.Unmarshal(output.Bytes(), &event); err != nil {
			t.Fatal(err)
		}
		for key, expected := range item.Expected {
			if key == "timestamp" || key == "exception" || key == "logger" || key == "attributes" {
				continue
			}
			if event[key] != expected {
				t.Errorf("%s: got %v want %v", key, event[key], expected)
			}
		}
		if timestamp, ok := event["timestamp"].(string); !ok || !strings.HasSuffix(timestamp, "Z") {
			t.Fatal(event)
		}
		if execution == "" && event["executionId"] != nil {
			t.Fatal("invented invocation identity")
		}
	}
}

func TestLogFieldsCannotOverrideRuntimeIdentity(t *testing.T) {
	t.Setenv("NANOFAAS_LOG_FORMAT", "json")
	var output bytes.Buffer
	ctx := WithInvocationContext(context.Background(), "real", "trace")
	Logger(ctx, NewLogger(&output, "function-runtime")).Info("reserved", "executionId", "fake", "level", "fake")
	var event map[string]any
	if err := json.Unmarshal(output.Bytes(), &event); err != nil {
		t.Fatal(err)
	}
	if event["executionId"] != "real" || event["level"] != "INFO" || event["attributes"].(map[string]any)["executionId"] != "fake" {
		t.Fatal(event)
	}
}

func TestConcurrentLoggersKeepIdentityAndHostLogger(t *testing.T) {
	t.Setenv("NANOFAAS_LOG_FORMAT", "json")
	original := slog.Default()
	var output bytes.Buffer
	base := NewLogger(&output, "runtime")
	var wg sync.WaitGroup
	for _, id := range []string{"one", "two"} {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			Logger(WithInvocationContext(context.Background(), id, "trace-"+id), base).Error("failure")
		}(id)
	}
	wg.Wait()
	base.Info("outside")
	for _, line := range strings.Split(strings.TrimSpace(output.String()), "\n") {
		var event map[string]any
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			t.Fatal(err)
		}
		if id, ok := event["executionId"].(string); ok && event["traceId"] != "trace-"+id {
			t.Fatal(event)
		}
		if event["message"] == "outside" && event["executionId"] != nil {
			t.Fatal("leaked context")
		}
	}
	if slog.Default() != original {
		t.Fatal("host logger replaced")
	}
}
