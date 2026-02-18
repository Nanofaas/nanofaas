package it.unimib.datai.nanofaas.controlplane.service;

public class AsyncQueueUnavailableException extends UnsupportedOperationException {

    public AsyncQueueUnavailableException() {
        super("Async invocation requires the async-queue module");
    }
}
