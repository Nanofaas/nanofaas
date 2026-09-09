package it.unimib.datai.nanofaas.controlplane.capacity;

import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;

/** A finite aggregate admission quota refused a new logical execution. */
public final class InvocationQuotaExceededException extends QueueFullException {
    private final Resource resource;

    public InvocationQuotaExceededException(Resource resource) {
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }

    public enum Resource {
        EXECUTION,
        INPUT
    }
}
