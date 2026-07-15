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
	Success bool       `json:"success"`
	Output  any        `json:"output"`
	Error   *ErrorInfo `json:"error"`
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
