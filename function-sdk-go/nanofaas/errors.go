package nanofaas

import "errors"

var (
	ErrExecutionIDMissing   = errors.New("execution ID not configured")
	ErrHandlerNotConfigured = errors.New("no handler configured")
	ErrHandlerTimeout       = errors.New("handler timed out")
)
