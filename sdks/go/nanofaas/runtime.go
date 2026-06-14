package nanofaas

import (
	"fmt"
	"log/slog"
	"sort"
	"strings"
)

type Option func(*Runtime)

type Runtime struct {
	settings           RuntimeSettings
	logger             *slog.Logger
	handlers           map[string]Handler
	callbackClient     *CallbackClient
	callbackDispatcher *CallbackDispatcher
	coldStart          *ColdStartTracker
	metrics            *runtimeMetrics
}

func NewRuntime(opts ...Option) *Runtime {
	rt := &Runtime{
		settings: LoadRuntimeSettingsFromEnv(),
		logger:   slog.Default(),
		handlers: map[string]Handler{},
	}
	for _, opt := range opts {
		opt(rt)
	}
	if rt.settings.Port == "" {
		rt.settings.Port = "8080"
	}
	if rt.settings.HandlerTimeout == 0 {
		rt.settings.HandlerTimeout = defaultHandlerTimeout
	}
	if rt.logger == nil {
		rt.logger = slog.Default()
	}
	rt.callbackClient = NewCallbackClient(rt.settings.CallbackURL)
	rt.callbackDispatcher = NewCallbackDispatcher(rt.callbackClient, 2, 128)
	rt.coldStart = NewColdStartTracker(nil)
	rt.metrics = newRuntimeMetrics()
	return rt
}

func WithSettings(settings RuntimeSettings) Option {
	return func(r *Runtime) {
		r.settings = settings
		if r.settings.Port == "" {
			r.settings.Port = "8080"
		}
		if r.settings.HandlerTimeout == 0 {
			r.settings.HandlerTimeout = defaultHandlerTimeout
		}
	}
}

func WithLogger(logger *slog.Logger) Option {
	return func(r *Runtime) {
		r.logger = logger
	}
}

func (r *Runtime) Register(name string, handler Handler) {
	r.handlers[name] = handler
}

func (r *Runtime) ResolveHandler() (Handler, error) {
	if len(r.handlers) == 0 {
		return nil, ErrHandlerNotConfigured
	}

	if r.settings.FunctionHandler != "" {
		handler, ok := r.handlers[r.settings.FunctionHandler]
		if !ok {
			return nil, fmt.Errorf("handler %q not found; available handlers: %s", r.settings.FunctionHandler, strings.Join(r.handlerNames(), ", "))
		}
		return handler, nil
	}

	if len(r.handlers) == 1 {
		for _, handler := range r.handlers {
			return handler, nil
		}
	}

	return nil, fmt.Errorf("multiple handlers found: %s; set FUNCTION_HANDLER to select one", strings.Join(r.handlerNames(), ", "))
}

func (r *Runtime) handlerNames() []string {
	names := make([]string, 0, len(r.handlers))
	for name := range r.handlers {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
