package nanofaas

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestInvocationResultHelpers(t *testing.T) {
	ok := Success(map[string]any{"ok": true})
	if ok.Error != nil || ok.Output == nil {
		t.Fatalf("expected success output without error")
	}

	err := Failure("HANDLER_ERROR", "boom")
	if err.Error == nil || err.Error.Code != "HANDLER_ERROR" {
		t.Fatalf("expected structured error")
	}
}

func TestInvocationResultJSONContract(t *testing.T) {
	for _, result := range []InvocationResult{Success("ok"), Failure("HANDLER_ERROR", "boom")} {
		body, err := json.Marshal(result)
		if err != nil {
			t.Fatal(err)
		}
		var fields map[string]any
		if err := json.Unmarshal(body, &fields); err != nil {
			t.Fatal(err)
		}
		for _, field := range []string{"success", "output", "error"} {
			if _, ok := fields[field]; !ok {
				t.Fatalf("missing %q in callback payload %s", field, body)
			}
		}
	}
}

func TestSuccessWithEnvelopeIsAlwaysSuccess(t *testing.T) {
	code := 503
	result := SuccessWithEnvelope("body", code, map[string]string{"Location": "/x"}, "base64")

	if !result.Success {
		t.Error("a function-decided result must be Success=true so it is never retried")
	}
	if result.StatusCode == nil || *result.StatusCode != 503 {
		t.Errorf("expected statusCode 503, got %v", result.StatusCode)
	}
	if result.Encoding != "base64" {
		t.Errorf("expected encoding base64, got %q", result.Encoding)
	}
}

func TestInvocationResultCallbackJSONUsesCamelCaseWireKeys(t *testing.T) {
	result := SuccessWithEnvelope("body", 201, map[string]string{"Location": "/x"}, "base64")
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	body := string(encoded)
	for _, key := range []string{`"statusCode"`, `"headers"`, `"encoding"`} {
		if !strings.Contains(body, key) {
			t.Errorf("callback body must contain %s, got %s", key, body)
		}
	}
	for _, wrong := range []string{`"status_code"`, `"Encoding"`, `"StatusCode"`} {
		if strings.Contains(body, wrong) {
			t.Errorf("callback body must not contain %s, got %s", wrong, body)
		}
	}
}

func TestPlainSuccessOmitsEnvelopeFields(t *testing.T) {
	encoded, err := json.Marshal(Success("body"))
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	body := string(encoded)
	for _, key := range []string{"statusCode", "headers", "encoding"} {
		if strings.Contains(body, key) {
			t.Errorf("a plain success must omit %s, got %s", key, body)
		}
	}
}
