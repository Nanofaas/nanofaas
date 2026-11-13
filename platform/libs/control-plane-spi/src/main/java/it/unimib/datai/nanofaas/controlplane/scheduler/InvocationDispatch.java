package it.unimib.datai.nanofaas.controlplane.scheduler;

/**
 * Dispatches an admitted attempt with its acquired ownership. The core owns the
 * transport, retry policy and terminal result; schedulers only select work.
 */
@FunctionalInterface
public interface InvocationDispatch {
    void dispatch(InvocationTask task);
}
