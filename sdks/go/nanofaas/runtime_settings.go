package nanofaas

import (
	"os"
	"strconv"
	"strings"
	"time"
)

const defaultHandlerTimeout = 30 * time.Second

type RuntimeSettings struct {
	Port            string
	ExecutionID     string
	TraceID         string
	CallbackURL     string
	FunctionHandler string
	HandlerTimeout  time.Duration
}

type InvocationContext struct {
	ExecutionID string
	TraceID     string
}

func LoadRuntimeSettingsFromEnv() RuntimeSettings {
	timeout := defaultHandlerTimeout
	if raw := os.Getenv("NANOFAAS_HANDLER_TIMEOUT"); raw != "" {
		if milliseconds, err := strconv.ParseInt(raw, 10, 64); err == nil && milliseconds > 0 {
			timeout = time.Duration(milliseconds) * time.Millisecond
		} else if parsed, err := time.ParseDuration(raw); err == nil && parsed > 0 {
			timeout = parsed
		}
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	return RuntimeSettings{
		Port:            port,
		ExecutionID:     os.Getenv("EXECUTION_ID"),
		TraceID:         os.Getenv("TRACE_ID"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
		FunctionHandler: os.Getenv("FUNCTION_HANDLER"),
		HandlerTimeout:  timeout,
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
