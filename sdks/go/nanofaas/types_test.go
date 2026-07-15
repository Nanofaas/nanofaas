package nanofaas

import (
	"encoding/json"
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
