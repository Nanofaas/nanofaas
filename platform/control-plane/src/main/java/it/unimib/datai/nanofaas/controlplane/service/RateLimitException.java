package it.unimib.datai.nanofaas.controlplane.service;

public class RateLimitException extends RuntimeException {
    // ponytail: no message, no cause, no suppression, no stack trace. This is a
    // control-flow signal that becomes a 429 - nobody ever reads its stack, and
    // filling one in costs a walk of a Reactor stack per rejected request.
    public RateLimitException() {
        super(null, null, false, false);
    }
}
