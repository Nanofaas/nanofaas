package nanofaas

import "testing"

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
