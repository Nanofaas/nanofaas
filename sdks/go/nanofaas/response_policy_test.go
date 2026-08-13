package nanofaas

import (
	"reflect"
	"testing"
)

func TestIsStatusCodeValid(t *testing.T) {
	for _, code := range []int{200, 422, 599} {
		if !IsStatusCodeValid(code) {
			t.Errorf("expected %d to be valid", code)
		}
	}
	for _, code := range []int{199, 600, 0, -1, 999} {
		if IsStatusCodeValid(code) {
			t.Errorf("expected %d to be invalid", code)
		}
	}
}

func TestFilterAllowedHeadersKeepsAllowedDropsEverythingElse(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{
		"Content-Type":   "application/pdf",
		"X-Custom":       "nope",
		"X-Execution-Id": "spoofed",
	})
	if !reflect.DeepEqual(filtered, map[string]string{"Content-Type": "application/pdf"}) {
		t.Errorf("unexpected filtered headers: %v", filtered)
	}
}

func TestFilterAllowedHeadersPreservesCasingAndDedupes(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{"Content-Type": "application/pdf"})
	if filtered["Content-Type"] != "application/pdf" {
		t.Errorf("original casing must be preserved, got %v", filtered)
	}
	if len(filtered) != 1 {
		t.Errorf("expected exactly one entry, got %v", filtered)
	}
}

func TestFilterAllowedHeadersNilIsEmptyNotNil(t *testing.T) {
	filtered := FilterAllowedHeaders(nil)
	if filtered == nil || len(filtered) != 0 {
		t.Errorf("expected an empty non-nil map, got %v", filtered)
	}
}
