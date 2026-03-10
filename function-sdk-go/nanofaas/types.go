package nanofaas

type InvocationRequest struct {
	Input    any               `json:"input"`
	Metadata map[string]string `json:"metadata,omitempty"`
}

type ErrorInfo struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type InvocationResult struct {
	Output any        `json:"output,omitempty"`
	Error  *ErrorInfo `json:"error,omitempty"`
}

func Success(output any) InvocationResult {
	return InvocationResult{Output: output}
}

func Failure(code, message string) InvocationResult {
	return InvocationResult{
		Error: &ErrorInfo{
			Code:    code,
			Message: message,
		},
	}
}
