package nanofaas

import (
	"context"
	"log/slog"
)

type contextKey string

const (
	executionIDContextKey contextKey = "execution_id"
	traceIDContextKey     contextKey = "trace_id"
)

func WithInvocationContext(ctx context.Context, executionID, traceID string) context.Context {
	if executionID != "" {
		ctx = context.WithValue(ctx, executionIDContextKey, executionID)
	}
	if traceID != "" {
		ctx = context.WithValue(ctx, traceIDContextKey, traceID)
	}
	return ctx
}

func ExecutionIDFromContext(ctx context.Context) (string, bool) {
	value, ok := ctx.Value(executionIDContextKey).(string)
	return value, ok && value != ""
}

func TraceIDFromContext(ctx context.Context) (string, bool) {
	value, ok := ctx.Value(traceIDContextKey).(string)
	return value, ok && value != ""
}

func Logger(ctx context.Context, base *slog.Logger) *slog.Logger {
	if base == nil {
		base = slog.Default()
	}

	attrs := make([]any, 0, 4)
	if executionID, ok := ExecutionIDFromContext(ctx); ok {
		attrs = append(attrs, "execution_id", executionID)
	}
	if traceID, ok := TraceIDFromContext(ctx); ok {
		attrs = append(attrs, "trace_id", traceID)
	}
	if len(attrs) == 0 {
		return base
	}
	return base.With(attrs...)
}
