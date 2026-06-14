package nanofaas

import "context"

// Handler is the function contract exposed by the NanoFaaS Go SDK.
//
// Handlers must honor ctx cancellation promptly. In warm mode the runtime can
// return a timeout response as soon as the context deadline expires, but Go
// cannot forcibly stop a goroutine that ignores cancellation.
type Handler func(ctx context.Context, req InvocationRequest) (any, error)
