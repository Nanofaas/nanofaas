package nanofaas

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"sync"
	"time"
)

// NewLogger configures a logger the caller owns, never the process-wide slog default.
func NewLogger(output io.Writer, service string) *slog.Logger {
	if override := os.Getenv("NANOFAAS_LOG_SERVICE"); override != "" {
		service = override
	}
	if service == "" {
		service = "function-runtime"
	}
	if os.Getenv("NANOFAAS_LOG_FORMAT") != "json" {
		return slog.New(slog.NewTextHandler(output, nil))
	}
	return slog.New(&jsonLogHandler{output: output, mu: &sync.Mutex{}, service: service})
}

type jsonLogHandler struct {
	output               io.Writer
	mu                   *sync.Mutex
	service              string
	executionID, traceID string
	attrs                []slog.Attr
	groups               []string
}

func (h *jsonLogHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= slog.LevelInfo
}
func (h *jsonLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	copy := *h
	copy.attrs = append(append([]slog.Attr(nil), h.attrs...), groupedAttributes(h.groups, attrs)...)
	return &copy
}
func (h *jsonLogHandler) WithGroup(name string) slog.Handler {
	copy := *h
	if name != "" {
		copy.groups = append(append([]string(nil), h.groups...), name)
	}
	return &copy
}
func groupedAttributes(groups []string, attrs []slog.Attr) []slog.Attr {
	for index := len(groups) - 1; index >= 0; index-- {
		attrs = []slog.Attr{{Key: groups[index], Value: slog.GroupValue(attrs...)}}
	}
	return attrs
}
func appendLogAttribute(fields map[string]any, attr slog.Attr) {
	value := attr.Value.Resolve()
	if value.Kind() == slog.KindGroup {
		target := fields
		if attr.Key != "" {
			if existing, ok := fields[attr.Key].(map[string]any); ok {
				target = existing
			} else {
				target = map[string]any{}
				fields[attr.Key] = target
			}
		}
		for _, child := range value.Group() {
			appendLogAttribute(target, child)
		}
	} else if attr.Key != "" {
		fields[attr.Key] = value.Any()
	}
}
func (h *jsonLogHandler) Handle(ctx context.Context, record slog.Record) error {
	level := "DEBUG"
	switch {
	case record.Level >= slog.LevelError:
		level = "ERROR"
	case record.Level >= slog.LevelWarn:
		level = "WARN"
	case record.Level >= slog.LevelInfo:
		level = "INFO"
	}
	event := map[string]any{"schemaVersion": 1, "timestamp": record.Time.UTC().Format(time.RFC3339Nano), "level": level, "service": h.service, "logger": "nanofaas", "message": record.Message}
	execution, trace := h.executionID, h.traceID
	if id, ok := ExecutionIDFromContext(ctx); ok {
		execution = id
	}
	if id, ok := TraceIDFromContext(ctx); ok {
		trace = id
	}
	if execution != "" {
		event["executionId"] = execution
	}
	if trace != "" {
		event["traceId"] = trace
	}
	attributes := map[string]any{}
	for _, attr := range h.attrs {
		appendLogAttribute(attributes, attr)
	}
	var attrs []slog.Attr
	record.Attrs(func(attr slog.Attr) bool { attrs = append(attrs, attr); return true })
	for _, attr := range groupedAttributes(h.groups, attrs) {
		appendLogAttribute(attributes, attr)
	}
	if len(attributes) > 0 {
		event["attributes"] = attributes
	}
	encoded, err := json.Marshal(event)
	if err != nil {
		delete(event, "attributes")
		encoded, err = json.Marshal(event)
		if err != nil {
			return err
		}
	}
	encoded = append(encoded, '\n')
	h.mu.Lock()
	defer h.mu.Unlock()
	_, err = h.output.Write(encoded)
	return err
}
