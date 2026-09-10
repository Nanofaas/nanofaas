package nanofaas

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

type saturationCorpus struct {
	SchemaVersion         string                         `json:"schemaVersion"`
	ContractDefinitions   map[string]corpusDefinitions   `json:"contractDefinitions"`
	Policy                corpusPolicy                   `json:"policy"`
	RuntimeConfigurations map[string]corpusRuntimeConfig `json:"runtimeConfigurations"`
	Scenarios             []corpusScenario               `json:"scenarios"`
	MutationTests         []map[string]any               `json:"mutationTests"`
}

type corpusPolicy struct {
	MaximumScenarioDeadlineMS int    `json:"maximumScenarioDeadlineMs"`
	DefinitionsRef            string `json:"definitionsRef"`
}

type corpusDefinitions struct {
	Vocabulary                    map[string][]string                `json:"vocabulary"`
	ActorActionCompatibility      map[string][]string                `json:"actorActionCompatibility"`
	HandlerLifecycles             map[string]corpusHandlerLifecycle  `json:"handlerLifecycles"`
	HandlerBehaviorLifecycleRefs  map[string][]string                `json:"handlerBehaviorLifecycleRefs"`
	CallbackLifecycles            map[string]corpusCallbackLifecycle `json:"callbackLifecycles"`
	CallbackBehaviorLifecycleRefs map[string][]string                `json:"callbackBehaviorLifecycleRefs"`
	WireOutcomes                  map[string]corpusWireOutcome       `json:"wireOutcomes"`
	CallbackEnvelopes             map[string]corpusCallbackEnvelope  `json:"callbackEnvelopes"`
	CallbackRequestTemplate       corpusCallbackRequestTemplate      `json:"callbackRequestTemplate"`
	SizeRelationOperators         map[string]string                  `json:"sizeRelationOperators"`
	FinalCountersRule             corpusExpression                   `json:"finalCountersRule"`
	IdentityRules                 []corpusRule                       `json:"identityRules"`
	CrossFieldRules               []corpusRule                       `json:"crossFieldRules"`
	ObservationSets               map[string][]string                `json:"observationSets"`
}
type corpusHandlerLifecycle struct {
	Started, CancelRequested *bool
	Terminal                 string `json:"terminal"`
}
type corpusCallbackLifecycle struct {
	Required, Attempted, Delivered *bool
	Attempts                       corpusExpression `json:"attempts"`
	Terminal                       string           `json:"terminal"`
}
type corpusWireOutcome struct {
	ConnectionOutcome string            `json:"connectionOutcome"`
	Status            int               `json:"status"`
	Body              any               `json:"body"`
	RequiredHeaders   map[string]string `json:"requiredHeaders"`
}
type corpusCallbackEnvelope struct {
	EmitsRequest *bool `json:"emitsRequest"`
	Payload      any   `json:"payload"`
}
type corpusCallbackRequestTemplate struct {
	Method  string                      `json:"method"`
	URL     corpusExpression            `json:"url"`
	Headers map[string]corpusExpression `json:"headers"`
}
type corpusExpression struct {
	Operator        string `json:"operator"`
	Value           any    `json:"value,omitempty"`
	Path            string `json:"path,omitempty"`
	CallbackURLPath string `json:"callbackUrlPath,omitempty"`
	ExecutionIDPath string `json:"executionIdPath,omitempty"`
	Suffix          string `json:"suffix,omitempty"`
	Field           string `json:"field,omitempty"`
}
type corpusRule struct {
	ID, Scope, Operator, Source string
	Expected                    *string `json:"expected,omitempty"`
	IgnoreNull                  *bool   `json:"ignoreNull,omitempty"`
	Increment                   *int    `json:"increment,omitempty"`
	Value                       any     `json:"value,omitempty"`
}

type corpusRuntimeConfig struct {
	MaxConcurrentHandlers, MaxInputBytes, MaxOutputBytes, MaxPendingCallbacks int
	MaxPendingCallbackBytes, HandlerTimeoutMS, CallbackAttemptTimeoutMS       int
	CallbackMaxAttempts, BodyReadTimeoutMS, ShutdownTimeoutMS                 int
}

func (c *corpusRuntimeConfig) UnmarshalJSON(body []byte) error {
	type wire struct {
		MaxConcurrentHandlers    int `json:"maxConcurrentHandlers"`
		MaxInputBytes            int `json:"maxInputBytes"`
		MaxOutputBytes           int `json:"maxOutputBytes"`
		MaxPendingCallbacks      int `json:"maxPendingCallbacks"`
		MaxPendingCallbackBytes  int `json:"maxPendingCallbackBytes"`
		HandlerTimeoutMS         int `json:"handlerTimeoutMs"`
		CallbackAttemptTimeoutMS int `json:"callbackAttemptTimeoutMs"`
		CallbackMaxAttempts      int `json:"callbackMaxAttempts"`
		BodyReadTimeoutMS        int `json:"bodyReadTimeoutMs"`
		ShutdownTimeoutMS        int `json:"shutdownTimeoutMs"`
	}
	var value wire
	if err := json.Unmarshal(body, &value); err != nil {
		return err
	}
	*c = corpusRuntimeConfig{value.MaxConcurrentHandlers, value.MaxInputBytes, value.MaxOutputBytes,
		value.MaxPendingCallbacks, value.MaxPendingCallbackBytes, value.HandlerTimeoutMS,
		value.CallbackAttemptTimeoutMS, value.CallbackMaxAttempts, value.BodyReadTimeoutMS,
		value.ShutdownTimeoutMS}
	return nil
}

type corpusScenario struct {
	ID, Kind, RuntimeConfigRef string
	ImplementationOwners       []string
	Requests                   []corpusRequest
	Backend                    corpusBackend
	Harness                    corpusHarness
	InitialCounters            map[string]int
	Expected                   corpusExpected
	DeadlineMS                 int
}

func (s *corpusScenario) UnmarshalJSON(body []byte) error {
	type wire struct {
		ID                   string          `json:"id"`
		Kind                 string          `json:"kind"`
		ImplementationOwners []string        `json:"implementationOwners"`
		RuntimeConfigRef     string          `json:"runtimeConfigRef"`
		Requests             []corpusRequest `json:"requests"`
		Backend              corpusBackend   `json:"backend"`
		Harness              corpusHarness   `json:"harness"`
		InitialCounters      map[string]int  `json:"initialCounters"`
		Expected             corpusExpected  `json:"expected"`
		DeadlineMS           int             `json:"deadlineMs"`
	}
	var value wire
	if err := json.Unmarshal(body, &value); err != nil {
		return err
	}
	*s = corpusScenario{value.ID, value.Kind, value.RuntimeConfigRef, value.ImplementationOwners,
		value.Requests, value.Backend, value.Harness, value.InitialCounters, value.Expected, value.DeadlineMS}
	return nil
}

type corpusRequest struct {
	ID, Role, Method, Path string
	Metadata               corpusMetadata
	Payload                corpusPayload
}
type corpusMetadata struct {
	ExecutionID     *string `json:"executionId"`
	DispatchAttempt *int    `json:"dispatchAttempt"`
	TraceID         *string `json:"traceId"`
	CallbackURL     *string `json:"callbackUrl"`
}
type corpusPayload struct {
	InputBytes           int    `json:"inputBytes"`
	RelationToInputLimit string `json:"relationToInputLimit"`
}

func (r *corpusRequest) UnmarshalJSON(body []byte) error {
	type wire struct {
		ID       string         `json:"id"`
		Role     string         `json:"role"`
		Method   string         `json:"method"`
		Path     string         `json:"path"`
		Metadata corpusMetadata `json:"metadata"`
		Payload  corpusPayload  `json:"payload"`
	}
	var value wire
	if err := json.Unmarshal(body, &value); err != nil {
		return err
	}
	*r = corpusRequest{value.ID, value.Role, value.Method, value.Path, value.Metadata, value.Payload}
	return nil
}

type corpusBackend struct {
	Handlers  []corpusHandlerBackend  `json:"handlers"`
	Callbacks []corpusCallbackBackend `json:"callbacks"`
}
type corpusHandlerBackend struct {
	RequestID             string  `json:"requestId"`
	Behavior              string  `json:"behavior"`
	OutputBytes           int     `json:"outputBytes"`
	OutputRelationToLimit string  `json:"outputRelationToLimit"`
	Barrier               *string `json:"barrier"`
}
type corpusCallbackBackend struct {
	RequestID string  `json:"requestId"`
	Behavior  string  `json:"behavior"`
	Barrier   *string `json:"barrier"`
}
type corpusHarness struct {
	Barriers []corpusBarrier `json:"barriers"`
	Actions  []corpusAction  `json:"actions"`
}
type corpusBarrier struct {
	ID           string `json:"id"`
	InitialState string `json:"initialState"`
}
type corpusAction struct {
	Sequence  int     `json:"sequence"`
	Actor     string  `json:"actor"`
	Action    string  `json:"action"`
	RequestID *string `json:"requestId"`
	Barrier   *string `json:"barrier"`
}
type corpusExpected struct {
	Responses         []corpusResponse         `json:"responses"`
	Handlers          []corpusHandlerExpected  `json:"handlers"`
	Callbacks         []corpusCallbackExpected `json:"callbacks"`
	Identity          corpusIdentityExpected   `json:"identity"`
	ObservationSetRef string                   `json:"observationSetRef"`
	Observations      []string                 `json:"observations"`
	FinalCounters     map[string]int           `json:"finalCounters"`
}
type corpusResponse struct {
	RequestID         string            `json:"requestId"`
	OutcomeRef        string            `json:"outcomeRef"`
	ConnectionOutcome string            `json:"connectionOutcome"`
	Status            int               `json:"status"`
	Body              any               `json:"body"`
	RequiredHeaders   map[string]string `json:"requiredHeaders"`
}
type corpusHandlerExpected struct {
	RequestID       string `json:"requestId"`
	LifecycleRef    string `json:"lifecycleRef"`
	Started         *bool  `json:"started"`
	CancelRequested *bool  `json:"cancelRequested"`
	Terminal        string `json:"terminal"`
}
type corpusCallbackExpected struct {
	RequestID         string                           `json:"requestId"`
	LifecycleRef      string                           `json:"lifecycleRef"`
	EnvelopeRef       string                           `json:"envelopeRef"`
	Required          *bool                            `json:"required"`
	Attempted         *bool                            `json:"attempted"`
	Delivered         *bool                            `json:"delivered"`
	Attempts          int                              `json:"attempts"`
	Terminal          string                           `json:"terminal"`
	DispatchAttempts  []int                            `json:"dispatchAttempts"`
	RequestProjection *corpusCallbackRequestProjection `json:"requestProjection"`
}
type corpusCallbackRequestProjection struct {
	Method  string            `json:"method"`
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
	Payload any               `json:"payload"`
}
type corpusIdentityExpected struct {
	ExecutionID             *string `json:"executionId"`
	RequestDispatchAttempts []int   `json:"requestDispatchAttempts"`
	RuntimeRedispatchCount  int     `json:"runtimeRedispatchCount"`
}

func TestConsumesSharedRuntimeSaturationWireContract(t *testing.T) {
	corpusPath := saturationCorpusPath(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	validator := filepath.Join(filepath.Dir(corpusPath), "validate_saturation_wire_corpus.py")
	output, err := exec.CommandContext(ctx, "python3", validator, "--run-mutations", corpusPath).CombinedOutput()
	if err != nil {
		t.Fatalf("shared validator failed: %v\n%s", err, output)
	}
	if ctx.Err() != nil {
		t.Fatal("shared validator exceeded finite adapter deadline")
	}

	body, err := os.ReadFile(corpusPath)
	if err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var corpus saturationCorpus
	if err := decoder.Decode(&corpus); err != nil {
		t.Fatal(err)
	}
	definitions, ok := corpus.ContractDefinitions[corpus.Policy.DefinitionsRef]
	if !ok || len(corpus.Scenarios) != len(definitions.Vocabulary["scenarioKinds"]) {
		t.Fatal("scenario projection mismatch")
	}
	for _, scenario := range corpus.Scenarios {
		if scenario.ID == "" || scenario.Kind == "" || scenario.RuntimeConfigRef == "" || len(scenario.Requests) == 0 ||
			len(scenario.Backend.Handlers) != len(scenario.Requests) || len(scenario.Backend.Callbacks) != len(scenario.Requests) ||
			len(scenario.Harness.Actions) == 0 || len(scenario.Expected.Observations) == 0 ||
			scenario.DeadlineMS <= 0 || scenario.DeadlineMS > corpus.Policy.MaximumScenarioDeadlineMS {
			t.Fatalf("incomplete typed projection for %q", scenario.ID)
		}
		for _, expected := range scenario.Expected.Handlers {
			if expected.Started == nil || expected.CancelRequested == nil {
				t.Fatalf("missing handler boolean in %q", scenario.ID)
			}
		}
		for _, expected := range scenario.Expected.Callbacks {
			if expected.Required == nil || expected.Attempted == nil || expected.Delivered == nil {
				t.Fatalf("missing callback boolean in %q", scenario.ID)
			}
			if expected.LifecycleRef == "" || expected.EnvelopeRef == "" ||
				(expected.Attempts > 0 && expected.RequestProjection == nil) {
				t.Fatalf("missing callback projection in %q", scenario.ID)
			}
		}
		for name, count := range scenario.Expected.FinalCounters {
			if name == "" || count != 0 {
				t.Fatalf("undrained final counter in %q", scenario.ID)
			}
		}
	}
	if len(corpus.MutationTests) == 0 {
		t.Fatal("mutation fixtures are required")
	}
}

func saturationCorpusPath(t *testing.T) string {
	t.Helper()
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate test source")
	}
	if configured := os.Getenv("NANOFAAS_SATURATION_CORPUS"); configured != "" {
		return configured
	}
	return filepath.Join(filepath.Dir(source), "..", "..", "runtime-contract", "saturation-wire-corpus.json")
}
