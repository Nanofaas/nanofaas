package nanofaas

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"testing"
)

type saturationWireCorpus struct {
	Version        int                    `json:"version"`
	Scope          string                 `json:"scope"`
	AdmissionPoint string                 `json:"admissionPoint"`
	ReleaseOn      []string               `json:"releaseOn"`
	RetryIdentity  map[string]string      `json:"retryIdentity"`
	Runner         saturationCorpusRunner `json:"runner"`
	Cases          []saturationWireCase   `json:"cases"`
}

type saturationCorpusRunner struct {
	RequiredCaseIDs       []string `json:"requiredCaseIds"`
	RequiredReleaseEvents []string `json:"requiredReleaseEvents"`
	MaximumCaseTimeoutMS  int      `json:"maximumCaseTimeoutMs"`
	MinimumHTTPStatus     int      `json:"minimumHttpStatus"`
	MaximumHTTPStatus     int      `json:"maximumHttpStatus"`
	NoSecondResponse      int      `json:"noSecondResponseStatus"`
}

type saturationWireCase struct {
	ID                  string `json:"id"`
	ImplementationOwner string `json:"implementationOwner"`
	Stimulus            string `json:"stimulus"`
	TimeoutMS           int    `json:"timeoutMs"`
	Expected            struct {
		HTTPStatus     int      `json:"httpStatus"`
		ErrorCode      string   `json:"errorCode"`
		Message        string   `json:"message"`
		Retryable      bool     `json:"retryable"`
		HandlerStarted bool     `json:"handlerStarted"`
		Callback       bool     `json:"callbackExpected"`
		Release        []string `json:"release"`
	} `json:"expected"`
}

func TestConsumesSharedRuntimeSaturationWireContract(t *testing.T) {
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate test source")
	}
	corpusPath := os.Getenv("NANOFAAS_SATURATION_CORPUS")
	if corpusPath == "" {
		corpusPath = filepath.Join(filepath.Dir(source), "..", "..", "runtime-contract", "saturation-wire-corpus.json")
	}
	body, err := os.ReadFile(corpusPath)
	if err != nil {
		t.Fatal(err)
	}
	var corpus saturationWireCorpus
	if err := json.Unmarshal(body, &corpus); err != nil {
		t.Fatal(err)
	}

	if corpus.Version <= 0 || corpus.Scope == "" || corpus.AdmissionPoint == "" {
		t.Fatalf("incomplete corpus metadata")
	}
	if stringSliceSet(corpus.ReleaseOn) != stringSliceSet(corpus.Runner.RequiredReleaseEvents) {
		t.Fatalf("release policy and runner contract differ")
	}
	for key, value := range corpus.RetryIdentity {
		if key == "" || value == "" {
			t.Fatalf("empty retry identity entry")
		}
	}

	actualIDs := make(map[string]struct{}, len(corpus.Cases))
	errorCode := regexp.MustCompile(`^[A-Z][A-Z0-9_]+$`)
	for _, testCase := range corpus.Cases {
		if _, exists := actualIDs[testCase.ID]; exists {
			t.Fatalf("duplicate case id %q", testCase.ID)
		}
		actualIDs[testCase.ID] = struct{}{}
		if testCase.ImplementationOwner == "" || testCase.Stimulus == "" || testCase.TimeoutMS <= 0 ||
			testCase.TimeoutMS > corpus.Runner.MaximumCaseTimeoutMS || len(testCase.Expected.Release) == 0 ||
			testCase.Expected.Message == "" || !errorCode.MatchString(testCase.Expected.ErrorCode) {
			t.Fatalf("case %q is incomplete", testCase.ID)
		}
		status := testCase.Expected.HTTPStatus
		if status != corpus.Runner.NoSecondResponse &&
			(status < corpus.Runner.MinimumHTTPStatus || status > corpus.Runner.MaximumHTTPStatus) {
			t.Fatalf("case %q has invalid HTTP status %d", testCase.ID, status)
		}
	}
	if stringMapSet(actualIDs) != stringSliceSet(corpus.Runner.RequiredCaseIDs) {
		t.Fatalf("actual and required case ids differ")
	}
}

func stringSliceSet(values []string) string {
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		seen[value] = struct{}{}
	}
	return stringMapSet(seen)
}

func stringMapSet(values map[string]struct{}) string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	return strings.Join(keys, "\x00")
}
