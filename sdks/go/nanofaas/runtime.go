package nanofaas

import (
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
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
	limits             *runtimeLimits
	lifecycle          *runtimeLifecycle
	startMu            sync.Mutex
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
	rt.settings = normalizedRuntimeSettings(rt.settings)
	if rt.logger == nil {
		rt.logger = slog.Default()
	}
	rt.metrics = newRuntimeMetrics()
	rt.callbackClient = NewCallbackClient(rt.settings.CallbackURL)
	rt.callbackClient.httpClient.Timeout = rt.settings.CallbackAttemptTimeout
	rt.callbackClient.retryDelays = callbackRetryDelays(rt.settings.CallbackMaxAttempts)
	rt.callbackDispatcher = rt.newOwnedCallbackDispatcher()
	rt.coldStart = NewColdStartTracker(nil)
	rt.limits = newRuntimeLimits(rt.settings)
	rt.lifecycle = newRuntimeLifecycle()
	return rt
}

func (r *Runtime) newOwnedCallbackDispatcher() *CallbackDispatcher {
	dispatcher := newCallbackDispatcher(r.callbackClient, 2, r.settings.MaxPendingCallbacks,
		r.settings.MaxPendingCallbacks, r.settings.MaxPendingCallbackBytes, r.settings.MaxCallbackPayloadBytes)
	dispatcher.onFailure = r.markCallbackDrop
	return dispatcher
}

func callbackRetryDelays(maxAttempts int) []int {
	maxAttempts = boundedInt(maxAttempts, defaultCallbackMaxAttempts, maximumCallbackAttempts)
	base := []int{100, 500, 2000}
	if maxAttempts <= len(base) {
		return append([]int(nil), base[:maxAttempts]...)
	}
	delays := append([]int(nil), base...)
	for len(delays) < maxAttempts {
		delays = append(delays, base[len(base)-1])
	}
	return delays
}

func WithSettings(settings RuntimeSettings) Option {
	return func(r *Runtime) {
		r.settings = normalizedRuntimeSettings(settings)
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
