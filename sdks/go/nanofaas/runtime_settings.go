package nanofaas

import (
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultHandlerTimeout                = 30 * time.Second
	defaultMaxConcurrentHandlers         = 32
	defaultMaxInputBytes           int64 = 1024 * 1024
	defaultMaxOutputBytes          int64 = 1024 * 1024
	defaultMaxPendingCallbacks           = 128
	defaultMaxPendingCallbackBytes int64 = 16 * 1024 * 1024
	defaultMaxCallbackPayloadBytes int64 = 2 * 1024 * 1024
	defaultBodyReadTimeout               = 5 * time.Second
	defaultCallbackAttemptTimeout        = 5 * time.Second
	defaultCallbackMaxAttempts           = 3
	defaultShutdownTimeout               = 5 * time.Second
	maximumHandlerTimeout                = time.Hour
	maximumConcurrentHandlers            = 4096
	maximumInputBytes              int64 = 64 * 1024 * 1024
	maximumOutputBytes             int64 = 64 * 1024 * 1024
	maximumPendingCallbacks              = 65536
	maximumPendingCallbackBytes    int64 = 1024 * 1024 * 1024
	maximumCallbackPayloadBytes    int64 = 64 * 1024 * 1024
	maximumBodyReadTimeout               = 5 * time.Minute
	maximumCallbackAttemptTimeout        = 5 * time.Minute
	maximumCallbackAttempts              = 10
	maximumShutdownTimeout               = 5 * time.Minute
)

type RuntimeSettings struct {
	Port                    string
	ExecutionID             string
	TraceID                 string
	CallbackURL             string
	FunctionHandler         string
	HandlerTimeout          time.Duration
	MaxConcurrentHandlers   int
	MaxInputBytes           int64
	MaxOutputBytes          int64
	MaxPendingCallbacks     int
	MaxPendingCallbackBytes int64
	MaxCallbackPayloadBytes int64
	BodyReadTimeout         time.Duration
	CallbackAttemptTimeout  time.Duration
	CallbackMaxAttempts     int
	ShutdownTimeout         time.Duration
}

type InvocationContext struct {
	ExecutionID string
	TraceID     string
}

func LoadRuntimeSettingsFromEnv() RuntimeSettings {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	return RuntimeSettings{
		Port:                    port,
		ExecutionID:             os.Getenv("EXECUTION_ID"),
		TraceID:                 os.Getenv("TRACE_ID"),
		CallbackURL:             os.Getenv("CALLBACK_URL"),
		FunctionHandler:         os.Getenv("FUNCTION_HANDLER"),
		HandlerTimeout:          durationSetting("NANOFAAS_HANDLER_TIMEOUT", defaultHandlerTimeout, maximumHandlerTimeout),
		MaxConcurrentHandlers:   intSetting("NANOFAAS_MAX_CONCURRENT_HANDLERS", defaultMaxConcurrentHandlers, maximumConcurrentHandlers),
		MaxInputBytes:           int64Setting("NANOFAAS_MAX_INPUT_BYTES", defaultMaxInputBytes, maximumInputBytes),
		MaxOutputBytes:          int64Setting("NANOFAAS_MAX_OUTPUT_BYTES", defaultMaxOutputBytes, maximumOutputBytes),
		MaxPendingCallbacks:     intSetting("NANOFAAS_MAX_PENDING_CALLBACKS", defaultMaxPendingCallbacks, maximumPendingCallbacks),
		MaxPendingCallbackBytes: int64Setting("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", defaultMaxPendingCallbackBytes, maximumPendingCallbackBytes),
		MaxCallbackPayloadBytes: int64Setting("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", defaultMaxCallbackPayloadBytes, maximumCallbackPayloadBytes),
		BodyReadTimeout:         durationSetting("NANOFAAS_BODY_READ_TIMEOUT", defaultBodyReadTimeout, maximumBodyReadTimeout),
		CallbackAttemptTimeout:  durationSetting("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", defaultCallbackAttemptTimeout, maximumCallbackAttemptTimeout),
		CallbackMaxAttempts:     intSetting("NANOFAAS_CALLBACK_MAX_ATTEMPTS", defaultCallbackMaxAttempts, maximumCallbackAttempts),
		ShutdownTimeout:         durationSetting("NANOFAAS_SHUTDOWN_TIMEOUT", defaultShutdownTimeout, maximumShutdownTimeout),
	}
}

func durationSetting(name string, fallback, maximum time.Duration) time.Duration {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback
	}
	if milliseconds, err := strconv.ParseInt(raw, 10, 64); err == nil && milliseconds > 0 &&
		milliseconds <= int64(maximum/time.Millisecond) {
		return time.Duration(milliseconds) * time.Millisecond
	}
	if parsed, err := time.ParseDuration(raw); err == nil && parsed > 0 && parsed <= maximum {
		return parsed
	}
	return fallback
}

func intSetting(name string, fallback, maximum int) int {
	value := int64Setting(name, int64(fallback), int64(maximum))
	if value > int64(^uint(0)>>1) || value > int64(maximum) {
		return fallback
	}
	return int(value)
}

func int64Setting(name string, fallback, maximum int64) int64 {
	raw := os.Getenv(name)
	value, err := strconv.ParseInt(raw, 10, 64)
	if raw == "" || err != nil || value <= 0 || value > maximum {
		return fallback
	}
	return value
}

func normalizedRuntimeSettings(settings RuntimeSettings) RuntimeSettings {
	defaults := RuntimeSettings{
		Port: "8080", HandlerTimeout: defaultHandlerTimeout,
		MaxConcurrentHandlers: defaultMaxConcurrentHandlers,
		MaxInputBytes:         defaultMaxInputBytes, MaxOutputBytes: defaultMaxOutputBytes,
		MaxPendingCallbacks:     defaultMaxPendingCallbacks,
		MaxPendingCallbackBytes: defaultMaxPendingCallbackBytes,
		MaxCallbackPayloadBytes: defaultMaxCallbackPayloadBytes,
		BodyReadTimeout:         defaultBodyReadTimeout,
		CallbackAttemptTimeout:  defaultCallbackAttemptTimeout,
		CallbackMaxAttempts:     defaultCallbackMaxAttempts,
		ShutdownTimeout:         defaultShutdownTimeout,
	}
	mergeRuntimeSettings(&defaults, settings)
	boundRuntimeSettings(&defaults)
	if defaults.MaxCallbackPayloadBytes > defaults.MaxPendingCallbackBytes {
		defaults.MaxCallbackPayloadBytes = defaults.MaxPendingCallbackBytes
	}
	return defaults
}

func boundRuntimeSettings(settings *RuntimeSettings) {
	settings.HandlerTimeout = boundedDuration(settings.HandlerTimeout, defaultHandlerTimeout, maximumHandlerTimeout)
	settings.MaxConcurrentHandlers = boundedInt(settings.MaxConcurrentHandlers, defaultMaxConcurrentHandlers, maximumConcurrentHandlers)
	settings.MaxInputBytes = boundedInt64(settings.MaxInputBytes, defaultMaxInputBytes, maximumInputBytes)
	settings.MaxOutputBytes = boundedInt64(settings.MaxOutputBytes, defaultMaxOutputBytes, maximumOutputBytes)
	settings.MaxPendingCallbacks = boundedInt(settings.MaxPendingCallbacks, defaultMaxPendingCallbacks, maximumPendingCallbacks)
	settings.MaxPendingCallbackBytes = boundedInt64(settings.MaxPendingCallbackBytes, defaultMaxPendingCallbackBytes, maximumPendingCallbackBytes)
	settings.MaxCallbackPayloadBytes = boundedInt64(settings.MaxCallbackPayloadBytes, defaultMaxCallbackPayloadBytes, maximumCallbackPayloadBytes)
	settings.BodyReadTimeout = boundedDuration(settings.BodyReadTimeout, defaultBodyReadTimeout, maximumBodyReadTimeout)
	settings.CallbackAttemptTimeout = boundedDuration(settings.CallbackAttemptTimeout, defaultCallbackAttemptTimeout, maximumCallbackAttemptTimeout)
	settings.CallbackMaxAttempts = boundedInt(settings.CallbackMaxAttempts, defaultCallbackMaxAttempts, maximumCallbackAttempts)
	settings.ShutdownTimeout = boundedDuration(settings.ShutdownTimeout, defaultShutdownTimeout, maximumShutdownTimeout)
}

func boundedInt(value, fallback, maximum int) int {
	if value <= 0 || value > maximum {
		return fallback
	}
	return value
}

func boundedInt64(value, fallback, maximum int64) int64 {
	if value <= 0 || value > maximum {
		return fallback
	}
	return value
}

func boundedDuration(value, fallback, maximum time.Duration) time.Duration {
	if value <= 0 || value > maximum {
		return fallback
	}
	return value
}

func mergeRuntimeSettings(target *RuntimeSettings, source RuntimeSettings) {
	if source.Port != "" {
		target.Port = source.Port
	}
	if source.ExecutionID != "" {
		target.ExecutionID = source.ExecutionID
	}
	if source.TraceID != "" {
		target.TraceID = source.TraceID
	}
	if source.CallbackURL != "" {
		target.CallbackURL = source.CallbackURL
	}
	if source.FunctionHandler != "" {
		target.FunctionHandler = source.FunctionHandler
	}
	if source.HandlerTimeout > 0 {
		target.HandlerTimeout = source.HandlerTimeout
	}
	if source.MaxConcurrentHandlers > 0 {
		target.MaxConcurrentHandlers = source.MaxConcurrentHandlers
	}
	if source.MaxInputBytes > 0 {
		target.MaxInputBytes = source.MaxInputBytes
	}
	if source.MaxOutputBytes > 0 {
		target.MaxOutputBytes = source.MaxOutputBytes
	}
	if source.MaxPendingCallbacks > 0 {
		target.MaxPendingCallbacks = source.MaxPendingCallbacks
	}
	if source.MaxPendingCallbackBytes > 0 {
		target.MaxPendingCallbackBytes = source.MaxPendingCallbackBytes
	}
	if source.MaxCallbackPayloadBytes > 0 {
		target.MaxCallbackPayloadBytes = source.MaxCallbackPayloadBytes
	}
	if source.BodyReadTimeout > 0 {
		target.BodyReadTimeout = source.BodyReadTimeout
	}
	if source.CallbackAttemptTimeout > 0 {
		target.CallbackAttemptTimeout = source.CallbackAttemptTimeout
	}
	if source.CallbackMaxAttempts > 0 {
		target.CallbackMaxAttempts = source.CallbackMaxAttempts
	}
	if source.ShutdownTimeout > 0 {
		target.ShutdownTimeout = source.ShutdownTimeout
	}
}

func (s RuntimeSettings) ResolveInvocationContext(headerExecutionID, headerTraceID string) InvocationContext {
	executionID := s.ExecutionID
	traceID := s.TraceID

	if strings.TrimSpace(headerExecutionID) != "" {
		executionID = strings.TrimSpace(headerExecutionID)
	}
	if strings.TrimSpace(headerTraceID) != "" {
		traceID = strings.TrimSpace(headerTraceID)
	}

	return InvocationContext{
		ExecutionID: executionID,
		TraceID:     traceID,
	}
}
