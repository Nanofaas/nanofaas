package nanofaas

import (
	"context"
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
	if settings.MaxConcurrentHandlers != 32 || settings.MaxInputBytes != 1024*1024 ||
		settings.MaxOutputBytes != 1024*1024 || settings.MaxPendingCallbacks != 128 ||
		settings.MaxPendingCallbackBytes != 16*1024*1024 || settings.MaxCallbackPayloadBytes != 2*1024*1024 {
		t.Fatalf("unexpected default limits: %+v", settings)
	}
	if settings.BodyReadTimeout != 5*time.Second || settings.CallbackAttemptTimeout != 5*time.Second ||
		settings.CallbackMaxAttempts != 3 || settings.ShutdownTimeout != 5*time.Second {
		t.Fatalf("unexpected default deadlines: %+v", settings)
	}
}

func TestLoadRuntimeSettingsFromEnvReadsFiniteLimits(t *testing.T) {
	t.Setenv("NANOFAAS_MAX_CONCURRENT_HANDLERS", "7")
	t.Setenv("NANOFAAS_MAX_INPUT_BYTES", "101")
	t.Setenv("NANOFAAS_MAX_OUTPUT_BYTES", "102")
	t.Setenv("NANOFAAS_MAX_PENDING_CALLBACKS", "8")
	t.Setenv("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "1000")
	t.Setenv("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "500")
	t.Setenv("NANOFAAS_BODY_READ_TIMEOUT", "41")
	t.Setenv("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "42")
	t.Setenv("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "4")
	t.Setenv("NANOFAAS_SHUTDOWN_TIMEOUT", "43")

	settings := LoadRuntimeSettingsFromEnv()

	if settings.MaxConcurrentHandlers != 7 || settings.MaxInputBytes != 101 || settings.MaxOutputBytes != 102 ||
		settings.MaxPendingCallbacks != 8 || settings.MaxPendingCallbackBytes != 1000 ||
		settings.MaxCallbackPayloadBytes != 500 || settings.BodyReadTimeout != 41*time.Millisecond ||
		settings.CallbackAttemptTimeout != 42*time.Millisecond || settings.CallbackMaxAttempts != 4 ||
		settings.ShutdownTimeout != 43*time.Millisecond {
		t.Fatalf("unexpected settings: %+v", settings)
	}
}

func TestLoadRuntimeSettingsFromEnvRejectsAbsurdAllocationAndDurationValues(t *testing.T) {
	for _, name := range []string{
		"NANOFAAS_MAX_CONCURRENT_HANDLERS", "NANOFAAS_MAX_INPUT_BYTES", "NANOFAAS_MAX_OUTPUT_BYTES",
		"NANOFAAS_MAX_PENDING_CALLBACKS", "NANOFAAS_MAX_PENDING_CALLBACK_BYTES",
		"NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "NANOFAAS_CALLBACK_MAX_ATTEMPTS",
	} {
		t.Setenv(name, "9223372036854775807")
	}
	for _, name := range []string{
		"NANOFAAS_HANDLER_TIMEOUT", "NANOFAAS_BODY_READ_TIMEOUT",
		"NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "NANOFAAS_SHUTDOWN_TIMEOUT",
	} {
		t.Setenv(name, "25h")
	}

	settings := LoadRuntimeSettingsFromEnv()

	if settings.MaxConcurrentHandlers != defaultMaxConcurrentHandlers ||
		settings.MaxInputBytes != defaultMaxInputBytes || settings.MaxOutputBytes != defaultMaxOutputBytes ||
		settings.MaxPendingCallbacks != defaultMaxPendingCallbacks ||
		settings.MaxPendingCallbackBytes != defaultMaxPendingCallbackBytes ||
		settings.MaxCallbackPayloadBytes != defaultMaxCallbackPayloadBytes ||
		settings.CallbackMaxAttempts != defaultCallbackMaxAttempts ||
		settings.HandlerTimeout != defaultHandlerTimeout || settings.BodyReadTimeout != defaultBodyReadTimeout ||
		settings.CallbackAttemptTimeout != defaultCallbackAttemptTimeout || settings.ShutdownTimeout != defaultShutdownTimeout {
		t.Fatalf("absurd settings escaped production bounds: %+v", settings)
	}
}

func TestNewRuntimeBoundsProgrammaticChannelCapacityAndRetryAttempts(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{
		MaxPendingCallbacks: 70_000,
		CallbackMaxAttempts: 1_000,
	}))
	t.Cleanup(func() { _ = rt.callbackDispatcher.Shutdown(context.Background()) })

	if got := cap(rt.callbackDispatcher.jobs); got != defaultMaxPendingCallbacks {
		t.Fatalf("unbounded callback channel capacity: %d", got)
	}
	if got := len(rt.callbackClient.retryDelays); got != defaultCallbackMaxAttempts {
		t.Fatalf("unbounded callback retry attempts: %d", got)
	}
}

func TestLoadRuntimeSettingsFromEnvReadsExplicitValues(t *testing.T) {
	t.Setenv("PORT", "9090")
	t.Setenv("EXECUTION_ID", "env-exec")
	t.Setenv("TRACE_ID", "env-trace")
	t.Setenv("CALLBACK_URL", "http://callback")
	t.Setenv("FUNCTION_HANDLER", "word-stats")
	t.Setenv("NANOFAAS_HANDLER_TIMEOUT", "45000")

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

func TestLoadRuntimeSettingsFromEnvKeepsDurationCompatibility(t *testing.T) {
	t.Setenv("NANOFAAS_HANDLER_TIMEOUT", "45s")

	settings := LoadRuntimeSettingsFromEnv()

	if settings.HandlerTimeout != 45*time.Second {
		t.Fatalf("unexpected compatible timeout: %s", settings.HandlerTimeout)
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
