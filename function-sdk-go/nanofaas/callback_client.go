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
	if strings.TrimSpace(c.baseURL) == "" || strings.TrimSpace(executionID) == "" {
		return false
	}

	body, err := json.Marshal(result)
	if err != nil {
		return false
	}

	url := c.callbackURL(executionID)
	for attempt := 0; attempt < len(c.retryDelays); attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			return false
		}
		req.Header.Set("Content-Type", "application/json")
		if strings.TrimSpace(traceID) != "" {
			req.Header.Set("X-Trace-Id", traceID)
		}

		resp, err := c.httpClient.Do(req)
		if err == nil && resp != nil {
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return true
			}
			if resp.StatusCode >= 400 && resp.StatusCode < 500 && resp.StatusCode != http.StatusRequestTimeout && resp.StatusCode != http.StatusTooManyRequests {
				return false
			}
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
