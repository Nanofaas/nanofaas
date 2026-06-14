package nanofaas

import (
	"net/http"
	"strconv"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type runtimeMetrics struct {
	registry           *prometheus.Registry
	invocationsTotal   *prometheus.CounterVec
	handlerDuration    prometheus.Histogram
	callbackDropsTotal prometheus.Counter
	coldStartsTotal    prometheus.Counter
}

func newRuntimeMetrics() *runtimeMetrics {
	registry := prometheus.NewRegistry()
	m := &runtimeMetrics{
		registry: registry,
		invocationsTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "nanofaas_runtime_invocations_total",
				Help: "Total runtime invocations by status.",
			},
			[]string{"status"},
		),
		handlerDuration: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Name:    "nanofaas_runtime_handler_duration_seconds",
				Help:    "Handler execution duration.",
				Buckets: prometheus.DefBuckets,
			},
		),
		callbackDropsTotal: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "nanofaas_runtime_callback_drops_total",
				Help: "Callbacks dropped because the dispatcher queue was full or canceled.",
			},
		),
		coldStartsTotal: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "nanofaas_runtime_cold_starts_total",
				Help: "Total cold starts observed by the runtime.",
			},
		),
	}
	registry.MustRegister(m.invocationsTotal, m.handlerDuration, m.callbackDropsTotal, m.coldStartsTotal)
	return m
}

func (r *Runtime) handleMetrics(w http.ResponseWriter, req *http.Request) {
	promhttp.HandlerFor(r.metrics.registry, promhttp.HandlerOpts{}).ServeHTTP(w, req)
}

func (r *Runtime) markInvocation(status string) {
	r.metrics.invocationsTotal.WithLabelValues(status).Inc()
}

func (r *Runtime) markHandlerDuration(seconds float64) {
	r.metrics.handlerDuration.Observe(seconds)
}

func (r *Runtime) markCallbackDrop() {
	r.metrics.callbackDropsTotal.Inc()
}

func (r *Runtime) markColdStart() {
	r.metrics.coldStartsTotal.Inc()
}

func formatInitDurationHeader(ms int64) string {
	return strconv.FormatInt(ms, 10)
}
