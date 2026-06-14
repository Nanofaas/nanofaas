package nanofaas

import (
	"os"
	"testing"
	"time"
)

func TestResolveInvocationContextPrefersHeadersOverEnvironment(t *testing.T) {
	settings := RuntimeSettings{
		ExecutionID: "env-exec",
		TraceID:     "env-trace",
	}

	got := settings.ResolveInvocationContext("header-exec", "header-trace")

	if got.ExecutionID != "header-exec" || got.TraceID != "header-trace" {
		t.Fatalf("unexpected resolved context: %+v", got)
	}
}

func TestLoadRuntimeSettingsFromEnvUsesDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("EXECUTION_ID", "")
	t.Setenv("TRACE_ID", "")
	t.Setenv("CALLBACK_URL", "")
	t.Setenv("FUNCTION_HANDLER", "")
	t.Setenv("NANOFAAS_HANDLER_TIMEOUT", "")

	settings := LoadRuntimeSettingsFromEnv()

	if settings.Port != "8080" {
		t.Fatalf("unexpected default port %q", settings.Port)
	}
	if settings.HandlerTimeout != 30*time.Second {
		t.Fatalf("unexpected default timeout %s", settings.HandlerTimeout)
	}
}

func TestLoadRuntimeSettingsFromEnvReadsExplicitValues(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("EXECUTION_ID", "env-exec")
	t.Setenv("TRACE_ID", "env-trace")
	t.Setenv("CALLBACK_URL", "http://callback")
	t.Setenv("FUNCTION_HANDLER", "word-stats")
	t.Setenv("NANOFAAS_HANDLER_TIMEOUT", "45s")

	settings := LoadRuntimeSettingsFromEnv()

	if settings.Port != "9090" ||
		settings.ExecutionID != "env-exec" ||
		settings.TraceID != "env-trace" ||
		settings.CallbackURL != "http://callback" ||
		settings.FunctionHandler != "word-stats" ||
		settings.HandlerTimeout != 45*time.Second {
		t.Fatalf("unexpected settings: %+v", settings)
	}
}

func TestLoadRuntimeSettingsFromEnvIgnoresInvalidTimeout(t *testing.T) {
	t.Setenv("NANOFAAS_HANDLER_TIMEOUT", "not-a-duration")

	settings := LoadRuntimeSettingsFromEnv()

	if settings.HandlerTimeout != 30*time.Second {
		t.Fatalf("expected fallback timeout, got %s", settings.HandlerTimeout)
	}
}

func TestResolveInvocationContextFallsBackToEnvironment(t *testing.T) {
	settings := RuntimeSettings{
		ExecutionID: "env-exec",
		TraceID:     "env-trace",
	}

	got := settings.ResolveInvocationContext("", "")

	if got.ExecutionID != "env-exec" || got.TraceID != "env-trace" {
		t.Fatalf("unexpected resolved context: %+v", got)
	}
}

func TestResolveInvocationContextTreatsBlankHeadersAsMissing(t *testing.T) {
	settings := RuntimeSettings{
		ExecutionID: "env-exec",
		TraceID:     "env-trace",
	}

	got := settings.ResolveInvocationContext("   ", "  ")

	if got.ExecutionID != "env-exec" || got.TraceID != "env-trace" {
		t.Fatalf("unexpected resolved context: %+v", got)
	}
}

func TestLoadRuntimeSettingsFromEnvDoesNotLeakHostEnvironment(t *testing.T) {
	for _, key := range []string{"PORT", "EXECUTION_ID", "TRACE_ID", "CALLBACK_URL", "FUNCTION_HANDLER", "NANOFAAS_HANDLER_TIMEOUT"} {
		if _, ok := os.LookupEnv(key); ok {
			t.Logf("env %s overridden in test", key)
		}
	}
}
