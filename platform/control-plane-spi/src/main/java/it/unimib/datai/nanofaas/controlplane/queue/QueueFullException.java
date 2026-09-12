package it.unimib.datai.nanofaas.controlplane.queue;

public class QueueFullException extends RuntimeException {
    // ponytail: no message, no cause, no suppression, no stack trace. This is a
    // control-flow signal that becomes a 429 - nobody ever reads its stack, and
    // filling one in costs a walk of a Reactor stack per rejected request.
    public QueueFullException() {
        super(null, null, false, false);
    }
}
