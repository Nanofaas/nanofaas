package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/**
 * Submits one already-admitted attempt to whatever actually executes it (issue #208, Task 10):
 * LOCAL/EXTERNAL/DEPLOYMENT dispatch, including any deployment wake-up wait. {@link
 * AttemptCoordinator} calls this once per attempt and otherwise never touches a dispatcher or a
 * readiness gate itself — mode selection, provider calls and wake-up waiting all live on the
 * implementation's side of this boundary, in the control plane's adapter.
 *
 * <p>{@code submit} may throw synchronously (a {@code RuntimeException} or {@code Error}) when
 * the attempt could not even be started; the coordinator handles that exactly as it handles an
 * attempt that started and then failed.
 *
 * <p>This is an internal runtime contract between the coordinator and the control plane's
 * transport adapter, not a public product extension point, and is deliberately kept out of the
 * SPI.
 */
public interface AttemptTransport {
    AttemptHandle submit(InvocationTask task);
}
