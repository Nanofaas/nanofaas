package nanofaas

import (
	"context"
	"errors"
	"net/http"
)

func (r *Runtime) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/invoke", r.handleInvoke)
	mux.HandleFunc("/health", r.handleHealth)
	mux.HandleFunc("/metrics", r.handleMetrics)
	return mux
}

func (r *Runtime) Start(ctx context.Context) error {
	server := &http.Server{
		Addr:    ":" + r.settings.Port,
		Handler: r.Handler(),
	}

	errCh := make(chan error, 1)
	go func() {
		errCh <- server.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		serverErr := server.Close()
		dispatcherErr := r.callbackDispatcher.Shutdown(ctx)
		if dispatcherErr != nil {
			return dispatcherErr
		}
		if serverErr != nil && !errors.Is(serverErr, http.ErrServerClosed) {
			return serverErr
		}
		return ctx.Err()
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) && ctx.Err() != nil {
			return ctx.Err()
		}
		return err
	}
}
