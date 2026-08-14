package nanofaas

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

type CallbackClient struct {
	baseURL     string
	httpClient  *http.Client
	retryDelays []int
}

func NewCallbackClient(baseURL string) *CallbackClient {
	return &CallbackClient{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 5 * time.Second,
		},
		retryDelays: []int{100, 500, 2000},
	}
}

func (c *CallbackClient) SendResult(ctx context.Context, executionID string, result InvocationResult, traceID string) bool {
	return c.SendResultWithDispatchAttempt(ctx, executionID, result, traceID, "")
}

func (c *CallbackClient) SendResultWithDispatchAttempt(ctx context.Context, executionID string, result InvocationResult, traceID, dispatchAttempt string) bool {
	if strings.TrimSpace(c.baseURL) == "" || strings.TrimSpace(executionID) == "" {
		return false
	}

	body, err := json.Marshal(result)
	if err != nil {
		return false
	}

	url := c.callbackURL(executionID)
	for attempt := 0; attempt < len(c.retryDelays); attempt++ {
		if success, final := c.sendCallbackRequest(ctx, url, body, traceID, dispatchAttempt); final {
			return success
		}

		if attempt == len(c.retryDelays)-1 {
			break
		}
		if !sleepWithContext(ctx, time.Duration(c.retryDelays[attempt])*time.Millisecond) {
			return false
		}
	}

	return false
}

// sendCallbackRequest performs one delivery attempt and reports the outcome:
// final indicates the send must stop (success, a non-retryable 4xx, or a request
// that could not be built); a non-final result means the attempt failed retryably.
func (c *CallbackClient) sendCallbackRequest(ctx context.Context, url string, body []byte, traceID, dispatchAttempt string) (success bool, final bool) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return false, true
	}
	setCallbackHeaders(req, traceID, dispatchAttempt)

	resp, err := c.httpClient.Do(req)
	if err == nil && resp != nil {
		resp.Body.Close()
		return classifyCallbackResponse(resp)
	}
	return false, false
}

func setCallbackHeaders(req *http.Request, traceID, dispatchAttempt string) {
	req.Header.Set(contentTypeHeader, "application/json")
	if strings.TrimSpace(traceID) != "" {
		req.Header.Set("X-Trace-Id", traceID)
	}
	if strings.TrimSpace(dispatchAttempt) != "" {
		req.Header.Set("X-Dispatch-Attempt", dispatchAttempt)
	}
}

// classifyCallbackResponse reports whether the response ends the send and, when
// it does, the outcome. 2xx is success; a non-retryable 4xx is a permanent
// failure; anything else (5xx, 408, 429) is retried.
func classifyCallbackResponse(resp *http.Response) (success bool, final bool) {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return true, true
	}
	if resp.StatusCode >= 400 && resp.StatusCode < 500 &&
		resp.StatusCode != http.StatusRequestTimeout && resp.StatusCode != http.StatusTooManyRequests {
		return false, true
	}
	return false, false
}

func (c *CallbackClient) callbackURL(executionID string) string {
	base := strings.TrimSpace(c.baseURL)
	base = strings.TrimRight(base, "/")
	if idx := strings.LastIndex(base, ":complete"); idx >= 0 {
		if slashIdx := strings.LastIndex(base[:idx], "/"); slashIdx >= 0 {
			base = base[:slashIdx]
		}
	}
	return base + "/" + executionID + ":complete"
}

func sleepWithContext(ctx context.Context, delay time.Duration) bool {
	if delay <= 0 {
		return ctx.Err() == nil
	}

	timer := time.NewTimer(delay)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
