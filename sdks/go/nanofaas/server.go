package nanofaas

import (
	"context"
	"errors"
	"net"
	"net/http"
)

var errRuntimeNotDrained = errors.New("runtime callback owner has not drained")

func (r *Runtime) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/invoke", r.handleInvoke)
	mux.HandleFunc("/health", r.handleHealth)
	mux.HandleFunc("/metrics", r.handleMetrics)
	return mux
}

func (r *Runtime) Start(ctx context.Context) error {
	r.startMu.Lock()
	defer r.startMu.Unlock()

	if r.callbackDispatcher.closed.Load() {
		if snapshot := r.callbackDispatcher.snapshot(); snapshot.pendingCallbacks != 0 ||
			snapshot.pendingCallbackBytes != 0 || snapshot.serializedCallbackBytes != 0 {
			return errRuntimeNotDrained
		}
		r.callbackDispatcher = r.newOwnedCallbackDispatcher()
	}
	r.limits.startAdmission()
	listener, err := net.Listen("tcp", ":"+r.settings.Port)
	if err != nil {
		r.lifecycle.set(runtimeStateStopping)
		r.limits.stopAdmission()
		cleanupCtx, cancel := context.WithTimeout(context.Background(), r.settings.ShutdownTimeout)
		defer cancel()
		shutdownErr := r.callbackDispatcher.Shutdown(cleanupCtx)
		return errors.Join(err, shutdownErr)
	}

	server := &http.Server{
		Handler:           r.Handler(),
		ReadHeaderTimeout: r.settings.BodyReadTimeout,
		ReadTimeout:       r.settings.BodyReadTimeout,
		WriteTimeout:      r.settings.HandlerTimeout + r.settings.ShutdownTimeout,
	}
	errCh := make(chan error, 1)
	go func() { errCh <- server.Serve(listener) }()
	r.lifecycle.set(runtimeStateRunning)

	select {
	case <-ctx.Done():
		return errors.Join(ctx.Err(), r.stop(server))
	case serveErr := <-errCh:
		if errors.Is(serveErr, http.ErrServerClosed) && ctx.Err() != nil {
			return errors.Join(ctx.Err(), r.stop(server))
		}
		return errors.Join(serveErr, r.stop(server))
	}
}

func (r *Runtime) stop(server *http.Server) error {
	r.lifecycle.set(runtimeStateStopping)
	r.limits.stopAdmission()
	stopCtx, cancel := context.WithTimeout(context.Background(), r.settings.ShutdownTimeout)
	defer cancel()

	serverErr := server.Shutdown(stopCtx)
	if serverErr != nil {
		_ = server.Close()
	}
	handlerErr := r.limits.waitForHandlers(stopCtx)
	dispatcherErr := r.callbackDispatcher.Shutdown(stopCtx)
	return errors.Join(serverErr, handlerErr, dispatcherErr)
}
