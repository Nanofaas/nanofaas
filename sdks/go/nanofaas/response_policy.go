package nanofaas

import "strings"

// allowedResponseHeaders mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/libs/common.
// Keep the two in sync.
var allowedResponseHeaders = map[string]struct{}{
	"content-type":        {},
	"location":            {},
	"cache-control":       {},
	"etag":                {},
	"content-disposition": {},
	"content-language":    {},
	"retry-after":         {},
	"vary":                {},
}

// IsStatusCodeValid reports whether a handler-chosen status code is legal to pass through.
func IsStatusCodeValid(statusCode int) bool {
	return statusCode >= 200 && statusCode <= 599
}

// FilterAllowedHeaders filters handler-supplied response headers down to the allow-list.
//
// At most one entry survives per header name, compared case-insensitively: HTTP header names are
// case-insensitive, so emitting both Content-Type and content-type would put two colliding entries
// on the response. The surviving key keeps its original casing.
func FilterAllowedHeaders(raw map[string]string) map[string]string {
	filtered := make(map[string]string, len(raw))
	seen := make(map[string]struct{}, len(raw))
	for key, value := range raw {
		lowerKey := strings.ToLower(key)
		if _, ok := allowedResponseHeaders[lowerKey]; !ok {
			continue
		}
		if _, dup := seen[lowerKey]; dup {
			continue
		}
		seen[lowerKey] = struct{}{}
		filtered[key] = value
	}
	return filtered
}
