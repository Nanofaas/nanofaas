package nanofaas

type InvocationRequest struct {
	Input    any               `json:"input"`
	Metadata map[string]string `json:"metadata,omitempty"`
}

type ErrorInfo struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// HandlerResponse is the optional envelope a handler may return instead of a plain value, to
// control the HTTP status code, response headers, and the base64 encoding marker.
//
// Detection is nominal: the runtime type-switches on this exact struct type. A handler returning
// any other value keeps today's behavior (implicit 200, no extra headers).
type HandlerResponse struct {
	Output     any
	StatusCode int
	Headers    map[string]string
	Encoding   string
}

// NewHandlerResponse builds an envelope with no headers and no encoding marker.
func NewHandlerResponse(output any, statusCode int) HandlerResponse {
	return HandlerResponse{Output: output, StatusCode: statusCode}
}

type InvocationResult struct {
	Success bool       `json:"success"`
	Output  any        `json:"output"`
	Error   *ErrorInfo `json:"error"`
	// Wire keys are camelCase to match platform/common's InvocationResult under default Jackson
	// naming. A mismatch here is silent — the field just vanishes on the control plane.
	StatusCode *int              `json:"statusCode,omitempty"`
	Headers    map[string]string `json:"headers,omitempty"`
	Encoding   string            `json:"encoding,omitempty"`
}

func Success(output any) InvocationResult {
	return InvocationResult{Success: true, Output: output}
}

func Failure(code, message string) InvocationResult {
	return InvocationResult{
		Error: &ErrorInfo{
			Code:    code,
			Message: message,
		},
	}
}

// SuccessWithEnvelope builds a function-decided result. Success is always true: retry is driven by
// !Success, so a function-decided response must never be retried whatever its status, 5xx included.
func SuccessWithEnvelope(output any, statusCode int, headers map[string]string, encoding string) InvocationResult {
	return InvocationResult{
		Success:    true,
		Output:     output,
		StatusCode: &statusCode,
		Headers:    headers,
		Encoding:   encoding,
	}
}
