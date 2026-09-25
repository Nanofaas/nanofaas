package nanofaas

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

type runtimeCorpusRequest struct {
	cancel context.CancelFunc
	done   chan struct{}
	result *httptest.ResponseRecorder
}

type runtimeCorpusHandlerObservation struct {
	started, cancelRequested bool
	terminal                 string
}

type runtimeCorpusCallbackObservation struct {
	attempts         int
	delivered        bool
	dispatchAttempts []int
	method, path     string
	headers          http.Header
	payload          any
}

type runtimeCorpusHarness struct {
	t        *testing.T
	ctx      context.Context
	scenario corpusScenario
	config   corpusRuntimeConfig
	runtime  *Runtime
	backend  *httptest.Server

	mu             sync.Mutex
	requests       map[string]*runtimeCorpusRequest
	handlers       map[string]*runtimeCorpusHandlerObservation
	callbacks      map[string]*runtimeCorpusCallbackObservation
	callbackChange chan struct{}
	barriers       map[string]chan struct{}
	handlerRelease map[string]chan struct{}

	runtimeCancel   context.CancelFunc
	runtimeResult   chan error
	heldCallback    *CallbackReservation
	heldHandler     *handlerReservation
	capacityRelease chan struct{}
	starts, stops   int
	logs            runtimeCorpusLog
}

type runtimeCorpusLog struct {
	mu sync.Mutex
	bytes.Buffer
}

func (l *runtimeCorpusLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.Buffer.Write(p)
}

func runRuntimeCorpusScenario(t *testing.T, corpus saturationCorpus, scenario corpusScenario) {
	t.Helper()
	config, ok := corpus.RuntimeConfigurations[scenario.RuntimeConfigRef]
	if !ok {
		t.Fatalf("missing runtime config %q", scenario.RuntimeConfigRef)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(scenario.DeadlineMS)*time.Millisecond)
	defer cancel()
	harness := newRuntimeCorpusHarness(t, ctx, scenario, config)
	defer harness.close()

	initialChecked := false
	for _, action := range scenario.Harness.Actions {
		if !initialChecked && (action.Action == "send-request" || action.Action == "probe-health" || action.Action == "begin-stop") {
			harness.verifyCounters("initial", scenario.InitialCounters)
			initialChecked = true
		}
		harness.run(action)
	}
	if !initialChecked {
		t.Fatal("initial counters were never observed")
	}
	harness.finishRequests()
	harness.releaseHandlers()
	harness.waitForDrain()
	harness.verify()
}

func newRuntimeCorpusHarness(t *testing.T, ctx context.Context, scenario corpusScenario,
	config corpusRuntimeConfig) *runtimeCorpusHarness {
	h := &runtimeCorpusHarness{
		t: t, ctx: ctx, scenario: scenario, config: config,
		requests: make(map[string]*runtimeCorpusRequest), handlers: make(map[string]*runtimeCorpusHandlerObservation),
		callbacks: make(map[string]*runtimeCorpusCallbackObservation), callbackChange: make(chan struct{}),
		barriers: make(map[string]chan struct{}), handlerRelease: make(map[string]chan struct{}),
	}
	for _, barrier := range scenario.Harness.Barriers {
		h.barriers[barrier.ID] = make(chan struct{})
	}
	h.backend = httptest.NewServer(http.HandlerFunc(h.receiveCallback))
	previousLogger := slog.Default()
	slog.SetDefault(slog.New(slog.NewJSONHandler(&h.logs, nil)))
	t.Cleanup(func() { slog.SetDefault(previousLogger) })
	settings := RuntimeSettings{
		Port: "0", CallbackURL: h.backend.URL,
		HandlerTimeout:        time.Duration(config.HandlerTimeoutMS) * time.Millisecond,
		MaxConcurrentHandlers: config.MaxConcurrentHandlers, MaxInputBytes: int64(config.MaxInputBytes),
		MaxOutputBytes: int64(config.MaxOutputBytes), MaxPendingCallbacks: config.MaxPendingCallbacks,
		MaxPendingCallbackBytes: int64(config.MaxPendingCallbackBytes),
		MaxCallbackPayloadBytes: int64(config.MaxPendingCallbackBytes),
		BodyReadTimeout:         time.Duration(config.BodyReadTimeoutMS) * time.Millisecond,
		CallbackAttemptTimeout:  time.Duration(config.CallbackAttemptTimeoutMS) * time.Millisecond,
		CallbackMaxAttempts:     config.CallbackMaxAttempts,
		ShutdownTimeout:         time.Duration(config.ShutdownTimeoutMS) * time.Millisecond,
	}
	if count := scenario.InitialCounters["pendingCallbacks"]; count > 0 {
		settings.MaxCallbackPayloadBytes = int64(scenario.InitialCounters["pendingCallbackBytes"] / count)
	}
	h.runtime = NewRuntime(WithSettings(settings))
	h.runtime.callbackClient.retryDelays = make([]int, config.CallbackMaxAttempts)
	h.runtime.Register("corpus", h.invokeHandler)
	return h
}

func (h *runtimeCorpusHarness) run(action corpusAction) {
	h.t.Helper()
	switch action.Action {
	case "start-runtime", "start-runtime-again":
		h.startRuntime()
	case "send-request", "probe-health":
		h.sendRequest(h.request(*action.RequestID))
	case "await-response":
		h.awaitResponse(*action.RequestID)
	case "await-callback":
		h.awaitCallback(*action.RequestID)
	case "await-barrier":
		h.awaitBarrier(*action.Barrier)
	case "release-barrier":
		h.closeBarrier(*action.Barrier)
	case "cancel-request":
		h.requests[*action.RequestID].cancel()
	case "fill-callback-capacity":
		h.fillCallbackCapacity()
	case "drain-callbacks":
		h.drainCallbackCapacity()
	case "fill-handler-capacity":
		h.fillHandlerCapacity()
	case "drain-handlers":
		h.drainHandlerCapacity()
	case "begin-stop":
		h.beginStop(action.Barrier)
	case "await-stop":
		h.awaitStop()
	case "control-plane-redispatch":
		// The next send-request is deliberately a new control-plane request.
	default:
		h.t.Fatalf("unsupported corpus action %q", action.Action)
	}
}

func (h *runtimeCorpusHarness) startRuntime() {
	if h.runtimeCancel != nil {
		h.t.Fatal("runtime already started")
	}
	ctx, cancel := context.WithCancel(context.Background())
	h.runtimeCancel = cancel
	h.runtimeResult = make(chan error, 1)
	go func() { h.runtimeResult <- h.runtime.Start(ctx) }()
	if err := h.runtime.waitForState(h.ctx, runtimeStateRunning); err != nil {
		h.t.Fatalf("runtime start: %v", err)
	}
	h.starts++
}

func (h *runtimeCorpusHarness) beginStop(barrier *string) {
	if h.runtimeCancel == nil {
		h.t.Fatal("runtime not started")
	}
	h.runtimeCancel()
	if err := h.runtime.waitForState(h.ctx, runtimeStateStopping); err != nil {
		h.t.Fatalf("runtime stop did not begin: %v", err)
	}
	if barrier != nil {
		h.closeBarrier(*barrier)
	}
}

func (h *runtimeCorpusHarness) awaitStop() {
	select {
	case err := <-h.runtimeResult:
		if !errors.Is(err, context.Canceled) {
			h.t.Fatalf("runtime stop: %v", err)
		}
		h.runtimeCancel = nil
		h.runtimeResult = nil
		h.stops++
	case <-h.ctx.Done():
		h.t.Fatalf("runtime stop deadline: %v", h.ctx.Err())
	}
}

func (h *runtimeCorpusHarness) sendRequest(request corpusRequest) {
	ctx, cancel := context.WithCancel(context.Background())
	body := io.Reader(nil)
	if request.Role == "invoke" {
		body = strings.NewReader(runtimeCorpusRequestBody(h.t, request.ID, request.Payload.InputBytes))
	}
	httpRequest := httptest.NewRequest(request.Method, request.Path, body).WithContext(ctx)
	if request.Metadata.ExecutionID != nil {
		httpRequest.Header.Set("X-Execution-Id", *request.Metadata.ExecutionID)
	}
	if request.Metadata.TraceID != nil {
		httpRequest.Header.Set("X-Trace-Id", *request.Metadata.TraceID)
	}
	if request.Metadata.DispatchAttempt != nil {
		httpRequest.Header.Set("X-Dispatch-Attempt", strconv.Itoa(*request.Metadata.DispatchAttempt))
	}
	observation := &runtimeCorpusRequest{cancel: cancel, done: make(chan struct{}), result: httptest.NewRecorder()}
	h.requests[request.ID] = observation
	go func() {
		h.runtime.Handler().ServeHTTP(observation.result, httpRequest)
		close(observation.done)
	}()
}

func runtimeCorpusRequestBody(t *testing.T, requestID string, size int) string {
	t.Helper()
	prefix := `{"input":"`
	suffix := fmt.Sprintf(`","metadata":{"requestId":%q}}`, requestID)
	if size < len(prefix)+len(suffix) {
		t.Fatalf("corpus input size %d cannot hold request identity", size)
	}
	body := prefix + strings.Repeat("x", size-len(prefix)-len(suffix)) + suffix
	if len(body) != size || !json.Valid([]byte(body)) {
		t.Fatalf("invalid corpus request body size=%d body=%q", len(body), body)
	}
	return body
}

func (h *runtimeCorpusHarness) awaitResponse(requestID string) {
	request := h.requests[requestID]
	select {
	case <-request.done:
	case <-h.ctx.Done():
		h.t.Fatalf("response %s deadline: %v", requestID, h.ctx.Err())
	}
}

func (h *runtimeCorpusHarness) invokeHandler(ctx context.Context, request InvocationRequest) (any, error) {
	requestID := request.Metadata["requestId"]
	backend := h.handlerBackend(requestID)
	h.mu.Lock()
	if h.handlers[requestID] != nil {
		h.mu.Unlock()
		h.t.Errorf("runtime redispatched request %q without a control-plane request", requestID)
		return nil, errors.New("unexpected runtime redispatch")
	}
	observation := &runtimeCorpusHandlerObservation{started: true}
	h.handlers[requestID] = observation
	release := make(chan struct{})
	h.handlerRelease[requestID] = release
	h.mu.Unlock()
	if backend.Barrier != nil {
		h.closeBarrier(*backend.Barrier)
	}

	switch backend.Behavior {
	case "succeed":
		if backend.OutputRelationToLimit == "above-limit" {
			observation.terminal = "output-rejected"
			return strings.Repeat("x", h.config.MaxOutputBytes+1), nil
		}
		observation.terminal = "succeeded"
		return map[string]string{"result": "ok"}, nil
	case "fail":
		observation.terminal = "failed"
		return nil, errors.New("corpus handler failure")
	case "block-until-cancelled":
		<-ctx.Done()
		h.mu.Lock()
		observation.cancelRequested = true
		if h.scenario.Kind == "handler-timeout" {
			observation.terminal = "timed-out"
		} else {
			observation.terminal = "cancelled"
		}
		h.mu.Unlock()
		<-release
		return nil, ctx.Err()
	default:
		return nil, fmt.Errorf("unexpected invoked behavior %q", backend.Behavior)
	}
}

func (h *runtimeCorpusHarness) fillCallbackCapacity() {
	reservation := h.runtime.callbackDispatcher.TryReserve()
	if reservation == nil {
		h.t.Fatal("could not fill callback capacity")
	}
	h.heldCallback = reservation
	if h.scenario.InitialCounters["serializedCallbackBytes"] == 0 {
		return
	}
	// Populate every envelope field and use a full-width integer so bounded
	// preflight and wire size coincide. This is a retained capacity fixture,
	// not one of the scenario's terminal callback projections.
	status := -9223372036854775807 - 1
	result := InvocationResult{Output: "", StatusCode: &status,
		Headers: map[string]string{"fixture": "capacity"}, Encoding: "identity"}
	body, err := json.Marshal(result)
	if err != nil {
		h.t.Fatal(err)
	}
	padding := h.scenario.InitialCounters["serializedCallbackBytes"] - len(body) - 1
	if padding < 0 {
		h.t.Fatal("capacity fixture cannot fit serialized size")
	}
	result.Output = strings.Repeat("x", padding)
	h.capacityRelease = make(chan struct{})
	if err := h.runtime.callbackDispatcher.SubmitReserved(context.Background(), reservation,
		"capacity", result, "", "1"); err != nil {
		h.t.Fatalf("queue capacity callback: %v", err)
	}
	h.awaitCallbackAttempt("capacity", 1)
	// The worker owns the reservation after submission.
	h.heldCallback = nil
	if h.scenario.InitialCounters["activeHandlers"] > 0 {
		h.fillHandlerCapacity()
	}
}

func (h *runtimeCorpusHarness) drainCallbackCapacity() {
	if h.capacityRelease != nil {
		select {
		case <-h.capacityRelease:
		default:
			close(h.capacityRelease)
		}
	}
	if h.heldCallback != nil {
		h.heldCallback.Release()
		h.heldCallback = nil
	}
}

func (h *runtimeCorpusHarness) fillHandlerCapacity() {
	h.heldHandler = h.runtime.limits.tryReserveHandler()
	if h.heldHandler == nil {
		h.t.Fatal("could not fill handler capacity")
	}
	h.heldHandler.retainInput(128)
	if h.runtime.callbackDispatcher.snapshot().pendingCallbacks == 0 && h.scenario.InitialCounters["pendingCallbacks"] > 0 {
		h.fillCallbackCapacity()
	}
}

func (h *runtimeCorpusHarness) drainHandlerCapacity() {
	if h.heldHandler != nil {
		h.heldHandler.release()
		h.heldHandler = nil
	}
	h.drainCallbackCapacity()
}

func (h *runtimeCorpusHarness) receiveCallback(w http.ResponseWriter, request *http.Request) {
	executionID := strings.TrimSuffix(strings.TrimPrefix(request.URL.Path, "/"), ":complete")
	if executionID == "capacity" {
		h.recordCallbackAttempt("capacity", request)
		select {
		case <-h.capacityRelease:
			w.WriteHeader(http.StatusNoContent)
		case <-request.Context().Done():
		}
		return
	}
	requestID := h.callbackRequestID(executionID, request.Header.Get("X-Dispatch-Attempt"))
	h.recordCallbackAttempt(requestID, request)
	if h.callbackBackend(requestID).Behavior == "retryable-failure" {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	h.mu.Lock()
	h.callbacks[requestID].delivered = true
	h.signalCallbackLocked()
	h.mu.Unlock()
	w.WriteHeader(http.StatusNoContent)
}

func (h *runtimeCorpusHarness) recordCallbackAttempt(requestID string, request *http.Request) {
	body, _ := io.ReadAll(request.Body)
	var payload any
	_ = json.Unmarshal(body, &payload)
	dispatchAttempt, _ := strconv.Atoi(request.Header.Get("X-Dispatch-Attempt"))
	h.mu.Lock()
	observation := h.callbacks[requestID]
	if observation == nil {
		observation = &runtimeCorpusCallbackObservation{}
		h.callbacks[requestID] = observation
	}
	observation.attempts++
	observation.dispatchAttempts = append(observation.dispatchAttempts, dispatchAttempt)
	observation.method, observation.path = request.Method, request.URL.Path
	observation.headers, observation.payload = request.Header.Clone(), payload
	h.signalCallbackLocked()
	h.mu.Unlock()
}

func (h *runtimeCorpusHarness) awaitCallback(requestID string) {
	expected := h.expectedCallback(requestID)
	h.awaitCallbackAttempt(requestID, expected.Attempts)
	if expected.Attempts > 0 {
		waitForDispatcherDrain(h.t, h.runtime.callbackDispatcher)
	}
}

func (h *runtimeCorpusHarness) awaitCallbackAttempt(requestID string, attempts int) {
	for {
		h.mu.Lock()
		observation := h.callbacks[requestID]
		if observation != nil && observation.attempts >= attempts {
			h.mu.Unlock()
			return
		}
		changed := h.callbackChange
		h.mu.Unlock()
		select {
		case <-changed:
		case <-h.ctx.Done():
			h.t.Fatalf("callback %s deadline: %v", requestID, h.ctx.Err())
		}
	}
}

func (h *runtimeCorpusHarness) signalCallbackLocked() {
	close(h.callbackChange)
	h.callbackChange = make(chan struct{})
}

func (h *runtimeCorpusHarness) awaitBarrier(name string) {
	barrier := h.barriers[name]
	select {
	case <-barrier:
	case <-h.ctx.Done():
		h.t.Fatalf("barrier %s deadline: %v", name, h.ctx.Err())
	}
}

func (h *runtimeCorpusHarness) closeBarrier(name string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	barrier := h.barriers[name]
	select {
	case <-barrier:
	default:
		close(barrier)
	}
}

func (h *runtimeCorpusHarness) finishRequests() {
	for requestID, expected := range h.expectedResponses() {
		request := h.requests[requestID]
		if request == nil {
			continue
		}
		if expected.ConnectionOutcome == "client-disconnected" {
			select {
			case <-request.done:
			case <-h.ctx.Done():
				h.t.Fatalf("cancelled request %s did not finish", requestID)
			}
		}
	}
}

func (h *runtimeCorpusHarness) releaseHandlers() {
	h.mu.Lock()
	for _, release := range h.handlerRelease {
		select {
		case <-release:
		default:
			close(release)
		}
	}
	h.mu.Unlock()
	h.drainHandlerCapacity()
}

func (h *runtimeCorpusHarness) waitForDrain() {
	drainCtx, cancel := context.WithTimeout(context.Background(), time.Until(deadlineFromContext(h.ctx)))
	defer cancel()
	if err := h.runtime.limits.waitForHandlers(drainCtx); err != nil {
		h.t.Fatalf("handler counters did not drain: %v", err)
	}
	if h.runtimeCancel != nil {
		h.runtimeCancel()
		h.awaitStop()
	}
	waitForDispatcherDrain(h.t, h.runtime.callbackDispatcher)
}

func deadlineFromContext(ctx context.Context) time.Time {
	deadline, ok := ctx.Deadline()
	if !ok {
		return time.Now().Add(time.Second)
	}
	return deadline
}

func (h *runtimeCorpusHarness) verify() {
	for _, expected := range h.scenario.Expected.Responses {
		h.verifyResponse(expected)
	}
	for _, expected := range h.scenario.Expected.Handlers {
		h.verifyHandler(expected)
	}
	for _, expected := range h.scenario.Expected.Callbacks {
		h.verifyCallback(expected)
	}
	h.verifyCounters("final", h.scenario.Expected.FinalCounters)
	h.verifyObservations()
}

func (h *runtimeCorpusHarness) verifyResponse(expected corpusResponse) {
	actual := h.requests[expected.RequestID]
	if expected.ConnectionOutcome == "client-disconnected" {
		if actual.result.Body.Len() != 0 {
			h.t.Fatalf("%s: disconnected request wrote %q", expected.RequestID, actual.result.Body.String())
		}
		return
	}
	if actual.result.Code != expected.Status {
		h.t.Fatalf("%s: status=%d want=%d body=%s", expected.RequestID, actual.result.Code, expected.Status, actual.result.Body.String())
	}
	var body any
	if err := json.Unmarshal(actual.result.Body.Bytes(), &body); err != nil {
		h.t.Fatalf("%s: invalid response JSON: %v", expected.RequestID, err)
	}
	if !reflect.DeepEqual(body, expected.Body) {
		h.t.Fatalf("%s: body=%#v want=%#v", expected.RequestID, body, expected.Body)
	}
	for name, value := range expected.RequiredHeaders {
		if got := actual.result.Header().Get(name); !strings.EqualFold(got, value) {
			h.t.Fatalf("%s: header %s=%q want=%q", expected.RequestID, name, got, value)
		}
	}
}

func (h *runtimeCorpusHarness) verifyHandler(expected corpusHandlerExpected) {
	h.mu.Lock()
	actual := h.handlers[expected.RequestID]
	h.mu.Unlock()
	if !*expected.Started {
		if actual != nil {
			h.t.Fatalf("%s: handler unexpectedly started: %+v", expected.RequestID, actual)
		}
		return
	}
	if actual == nil || !actual.started || actual.cancelRequested != *expected.CancelRequested || actual.terminal != expected.Terminal {
		h.t.Fatalf("%s: handler=%+v want=%+v", expected.RequestID, actual, expected)
	}
}

func (h *runtimeCorpusHarness) verifyCallback(expected corpusCallbackExpected) {
	h.mu.Lock()
	actual := h.callbacks[expected.RequestID]
	h.mu.Unlock()
	if expected.Attempts == 0 {
		if actual != nil {
			h.t.Fatalf("%s: unexpected callback %+v", expected.RequestID, actual)
		}
		return
	}
	if actual == nil || actual.attempts != expected.Attempts || actual.delivered != *expected.Delivered ||
		!reflect.DeepEqual(actual.dispatchAttempts, expected.DispatchAttempts) {
		h.t.Fatalf("%s: callback=%+v want=%+v", expected.RequestID, actual, expected)
	}
	h.verifyCallbackProjection(expected, actual)
}

func (h *runtimeCorpusHarness) verifyObservations() {
	observed := make(map[string]bool)
	h.observeRequests(observed)
	h.observeOwners(observed)
	observed["stop-complete"] = h.stops > 0
	observed["restart-complete"] = h.starts > 1 && h.stops > 0
	observed["counters-zero"] = h.runtime.limits.snapshot().activeHandlers == 0 &&
		h.runtime.callbackDispatcher.snapshot() == (callbackDispatcherSnapshot{})
	h.observeFailureMetric(observed)
	h.observeStructuredLog(observed)
	for _, name := range h.scenario.Expected.Observations {
		if !observed[name] {
			h.t.Errorf("missing actual observation %q", name)
		}
	}
	h.t.Logf("observations=%v", observed)
}

func (h *runtimeCorpusHarness) observeRequests(observed map[string]bool) {
	for id, request := range h.requests {
		observed["wire-response"] = observed["wire-response"] || request.result.Body.Len() > 0
		observed["no-wire-response"] = observed["no-wire-response"] || (request.result.Body.Len() == 0 && request.result.Code == 200)
		if h.request(id).Role == "health" && request.result.Code == 200 {
			observed["health-response"] = true
		}
	}
}

func (h *runtimeCorpusHarness) observeOwners(observed map[string]bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, handler := range h.handlers {
		observed["handler-start"] = observed["handler-start"] || handler.started
		observed["handler-cancel"] = observed["handler-cancel"] || handler.cancelRequested
	}
	for id, callback := range h.callbacks {
		if id == "capacity" {
			continue
		}
		observed["callback-attempt"] = observed["callback-attempt"] || callback.attempts > 0
		observed["callback-delivery"] = observed["callback-delivery"] || callback.delivered
	}
	// Every handler start must correspond to one explicit harness request;
	// invokeHandler separately rejects duplicate starts below.
	observed["runtime-redispatch-zero"] = len(h.handlers) <= len(h.requests)
}

func (h *runtimeCorpusHarness) observeFailureMetric(observed map[string]bool) {
	families, err := h.runtime.metrics.registry.Gather()
	if err != nil {
		h.t.Fatal(err)
	}
	for _, family := range families {
		if family.GetName() != "nanofaas_runtime_callback_drops_total" {
			continue
		}
		for _, metric := range family.Metric {
			observed["callback-failure-metric"] = metric.GetCounter().GetValue() > 0
		}
	}
}

func (h *runtimeCorpusHarness) observeStructuredLog(observed map[string]bool) {
	h.logs.mu.Lock()
	defer h.logs.mu.Unlock()
	decoder := json.NewDecoder(bytes.NewReader(h.logs.Bytes()))
	for {
		var record map[string]any
		if err := decoder.Decode(&record); err == io.EOF {
			return
		} else if err != nil {
			h.t.Fatal(err)
		}
		if record["msg"] == "callback delivery exhausted" && record["execution_id"] != nil {
			observed["structured-log"] = true
		}
	}
}

func (h *runtimeCorpusHarness) verifyCounters(phase string, expected map[string]int) {
	limits := h.runtime.limits.snapshot()
	callbacks := h.runtime.callbackDispatcher.snapshot()
	actual := map[string]int{
		"activeHandlers": limits.activeHandlers, "inputBytes": int(limits.inputBytes),
		"outputBytes": int(limits.outputBytes), "pendingCallbacks": callbacks.pendingCallbacks,
		"pendingCallbackBytes":    int(callbacks.pendingCallbackBytes),
		"serializedCallbackBytes": int(callbacks.serializedCallbackBytes),
	}
	if !reflect.DeepEqual(actual, expected) {
		h.t.Fatalf("%s counters=%v want=%v", phase, actual, expected)
	}
	h.t.Logf("%s counters=%v", phase, actual)
}

func (h *runtimeCorpusHarness) verifyCallbackProjection(expected corpusCallbackExpected,
	actual *runtimeCorpusCallbackObservation) {
	projection := expected.RequestProjection
	if projection == nil {
		return
	}
	expectedURL, err := url.Parse(projection.URL)
	if err != nil {
		h.t.Fatal(err)
	}
	if actual.method != projection.Method || actual.path != expectedURL.Path ||
		!reflect.DeepEqual(actual.payload, projection.Payload) {
		h.t.Fatalf("%s: callback projection method=%s path=%s payload=%#v want=%+v",
			expected.RequestID, actual.method, actual.path, actual.payload, projection)
	}
	for name, value := range projection.Headers {
		if got := actual.headers.Get(name); !strings.EqualFold(got, value) {
			h.t.Fatalf("%s: callback header %s=%q want=%q", expected.RequestID, name, got, value)
		}
	}
}

func (h *runtimeCorpusHarness) close() {
	h.drainCallbackCapacity()
	h.releaseHandlers()
	if h.runtimeCancel != nil {
		h.runtimeCancel()
		select {
		case <-h.runtimeResult:
		case <-time.After(time.Second):
		}
	}
	if h.backend != nil {
		h.backend.Close()
	}
}

func (h *runtimeCorpusHarness) request(id string) corpusRequest {
	for _, request := range h.scenario.Requests {
		if request.ID == id {
			return request
		}
	}
	h.t.Fatalf("missing request %q", id)
	return corpusRequest{}
}

func (h *runtimeCorpusHarness) handlerBackend(id string) corpusHandlerBackend {
	for _, backend := range h.scenario.Backend.Handlers {
		if backend.RequestID == id {
			return backend
		}
	}
	h.t.Fatalf("missing handler backend %q", id)
	return corpusHandlerBackend{}
}

func (h *runtimeCorpusHarness) callbackBackend(id string) corpusCallbackBackend {
	for _, backend := range h.scenario.Backend.Callbacks {
		if backend.RequestID == id {
			return backend
		}
	}
	h.t.Fatalf("missing callback backend %q", id)
	return corpusCallbackBackend{}
}

func (h *runtimeCorpusHarness) callbackRequestID(executionID, dispatchAttempt string) string {
	for _, request := range h.scenario.Requests {
		if request.Metadata.ExecutionID != nil && *request.Metadata.ExecutionID == executionID &&
			request.Metadata.DispatchAttempt != nil && strconv.Itoa(*request.Metadata.DispatchAttempt) == dispatchAttempt {
			return request.ID
		}
	}
	return executionID
}

func (h *runtimeCorpusHarness) expectedCallback(id string) corpusCallbackExpected {
	for _, callback := range h.scenario.Expected.Callbacks {
		if callback.RequestID == id {
			return callback
		}
	}
	h.t.Fatalf("missing expected callback %q", id)
	return corpusCallbackExpected{}
}

func (h *runtimeCorpusHarness) expectedResponses() map[string]corpusResponse {
	result := make(map[string]corpusResponse)
	for _, response := range h.scenario.Expected.Responses {
		result[response.RequestID] = response
	}
	return result
}
