package nanofaas

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type observableExpandingMarshaler struct {
	called *atomic.Bool
}

var expandingMapKeyCalled atomic.Bool

type observableExpandingMapKey int

var bypassMarshalerCalled atomic.Bool

type nilSliceValueJSONMarshaler []byte
type nilMapValueTextMarshaler map[string]string
type nilMapPointerJSONMarshaler map[string]string
type nilSlicePointerTextMarshaler []byte
type addressablePointerTextMarshaler string

func (observableExpandingMapKey) MarshalText() ([]byte, error) {
	expandingMapKeyCalled.Store(true)
	return []byte(strings.Repeat("x", 1<<20)), nil
}

func (nilSliceValueJSONMarshaler) MarshalJSON() ([]byte, error) {
	bypassMarshalerCalled.Store(true)
	return []byte(`"` + strings.Repeat("x", 1<<20) + `"`), nil
}

func (nilMapValueTextMarshaler) MarshalText() ([]byte, error) {
	bypassMarshalerCalled.Store(true)
	return []byte(strings.Repeat("x", 1<<20)), nil
}

func (*nilMapPointerJSONMarshaler) MarshalJSON() ([]byte, error) {
	bypassMarshalerCalled.Store(true)
	return []byte(`"` + strings.Repeat("x", 1<<20) + `"`), nil
}

func (*nilSlicePointerTextMarshaler) MarshalText() ([]byte, error) {
	bypassMarshalerCalled.Store(true)
	return []byte(strings.Repeat("x", 1<<20)), nil
}

func (*addressablePointerTextMarshaler) MarshalText() ([]byte, error) {
	bypassMarshalerCalled.Store(true)
	return []byte(strings.Repeat("x", 1<<20)), nil
}

func (m observableExpandingMarshaler) MarshalJSON() ([]byte, error) {
	m.called.Store(true)
	return []byte(`"` + strings.Repeat("x", 1<<20) + `"`), nil
}

func TestEncodeJSONBoundedRejectsCustomMarshalerBeforeItCanExpand(t *testing.T) {
	var called atomic.Bool

	_, err := encodeJSONBounded(observableExpandingMarshaler{called: &called}, 64)

	if err == nil || errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected a non-size serialization error, got %v", err)
	}
	if called.Load() {
		t.Fatal("custom marshaler ran before bounded encoding rejected it")
	}
}

func TestEncodeJSONBoundedRejectsCustomMapKeyBeforeItCanExpand(t *testing.T) {
	expandingMapKeyCalled.Store(false)

	_, err := encodeJSONBounded(map[observableExpandingMapKey]string{1: "value"}, 64)

	if err == nil || errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected a non-size serialization error, got %v", err)
	}
	if expandingMapKeyCalled.Load() {
		t.Fatal("custom map-key marshaler ran before bounded encoding rejected it")
	}
}

func TestEncodeJSONBoundedChecksCustomMethodSetsBeforeNilShortcuts(t *testing.T) {
	tests := []struct {
		name  string
		value any
	}{
		{"slice-value-json", nilSliceValueJSONMarshaler(nil)},
		{"nil-pointer-to-slice-value-json", (*nilSliceValueJSONMarshaler)(nil)},
		{"map-value-text", nilMapValueTextMarshaler(nil)},
		{"nil-pointer-to-map-value-text", (*nilMapValueTextMarshaler)(nil)},
		{"map-pointer-json", &struct{ Value nilMapPointerJSONMarshaler }{}},
		{"slice-pointer-text", &struct{ Value nilSlicePointerTextMarshaler }{}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			bypassMarshalerCalled.Store(false)
			_, err := encodeJSONBounded(test.value, 64)
			if !errors.Is(err, errCustomJSONMarshaler) {
				t.Fatalf("expected custom-marshaler rejection, got %v", err)
			}
			if bypassMarshalerCalled.Load() {
				t.Fatal("custom marshaler bypassed preflight")
			}
		})
	}
}

func TestEncodeJSONBoundedRejectsAddressablePointerTextMarshalerBeforeInvocation(t *testing.T) {
	bypassMarshalerCalled.Store(false)
	value := &struct {
		Field addressablePointerTextMarshaler
	}{Field: "small"}

	_, err := encodeJSONBounded(value, 64)

	if !errors.Is(err, errCustomJSONMarshaler) {
		t.Fatalf("expected custom-marshaler rejection, got %v", err)
	}
	if bypassMarshalerCalled.Load() {
		t.Fatal("addressable pointer TextMarshaler ran before rejection")
	}
}

func TestEncodeJSONBoundedPreservesKnownBoundedStandardTypes(t *testing.T) {
	value := struct {
		At     time.Time
		IP     net.IP
		Prefix netip.Prefix
	}{
		At:     time.Date(2026, time.September, 11, 10, 30, 0, 123000000, time.UTC),
		IP:     net.ParseIP("192.0.2.1"),
		Prefix: netip.MustParsePrefix("2001:db8::/32"),
	}

	body, err := encodeJSONBounded(value, 256)

	if err != nil {
		t.Fatal(err)
	}
	const want = "{\"At\":\"2026-09-11T10:30:00.123Z\",\"IP\":\"192.0.2.1\",\"Prefix\":\"2001:db8::/32\"}\n"
	if string(body) != want {
		t.Fatalf("body=%q want=%q", body, want)
	}
}

func TestEncodeJSONBoundedRejectsLargeSupportedValue(t *testing.T) {
	_, err := encodeJSONBounded(strings.Repeat("x", 1024), 64)
	if !errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected payload limit error, got %v", err)
	}
}

func TestEncodeJSONBoundedRejectsCyclesAsSerializationError(t *testing.T) {
	value := map[string]any{}
	value["self"] = value

	_, err := encodeJSONBounded(value, 1024)

	if err == nil || errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected cycle serialization error, got %v", err)
	}
}

func TestEncodeJSONBoundedRejectsExcessiveDepthBeforeEncoding(t *testing.T) {
	var value any = nil
	for range 200 {
		value = []any{value}
	}

	_, err := encodeJSONBounded(value, 1<<20)

	if err == nil || errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected depth serialization error, got %v", err)
	}
}

func TestEncodeJSONBoundedSupportsRawMessageWithinBound(t *testing.T) {
	body, err := encodeJSONBounded(map[string]any{"raw": json.RawMessage(`{"ok":true}`)}, 256)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(body, &decoded); err != nil || decoded["raw"].(map[string]any)["ok"] != true {
		t.Fatalf("unexpected encoded body %q: %v", body, err)
	}
}
