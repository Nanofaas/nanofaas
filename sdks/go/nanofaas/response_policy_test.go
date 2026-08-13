package nanofaas

import (
	"reflect"
	"strings"
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

func TestFilterAllowedHeadersPreservesCasing(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{"Content-Type": "application/pdf"})
	if filtered["Content-Type"] != "application/pdf" {
		t.Errorf("original casing must be preserved, got %v", filtered)
	}
	if len(filtered) != 1 {
		t.Errorf("expected exactly one entry, got %v", filtered)
	}
}

// TestFilterAllowedHeadersDedupesCaseInsensitiveCollision feeds two casings of the same
// header name (plus an allowed and a disallowed distractor) and asserts only one entry
// survives. It intentionally does NOT assert which casing/value wins: Go map iteration
// order is undefined, so that assertion would be flaky. The count is deterministic
// regardless of iteration order, which is what makes this test meaningful.
func TestFilterAllowedHeadersDedupesCaseInsensitiveCollision(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{
		"Content-Type": "application/pdf",
		"content-type": "text/plain",
		"ETag":         `"abc123"`,
		"X-Custom":     "nope",
	})
	count := 0
	for key := range filtered {
		if strings.EqualFold(key, "content-type") {
			count++
		}
	}
	if count != 1 {
		t.Errorf("expected exactly one content-type entry (any casing), got %d in %v", count, filtered)
	}
	if len(filtered) != 2 {
		t.Errorf("expected content-type (deduped) + etag = 2 entries, got %v", filtered)
	}
}

func TestFilterAllowedHeadersNilIsEmptyNotNil(t *testing.T) {
	filtered := FilterAllowedHeaders(nil)
	if filtered == nil || len(filtered) != 0 {
		t.Errorf("expected an empty non-nil map, got %v", filtered)
	}
}
