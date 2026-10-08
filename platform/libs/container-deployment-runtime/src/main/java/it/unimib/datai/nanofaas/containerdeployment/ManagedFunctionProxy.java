package it.unimib.datai.nanofaas.containerdeployment;

import java.time.Duration;
import java.util.List;

public interface ManagedFunctionProxy extends AutoCloseable {
    String endpointUrl();

    void updateBackends(List<String> backendBaseUrls);

    /**
     * Pushes the per-function tuning to the proxy: the maximum number of invocation requests that
     * may be forwarded concurrently (the admission bound) and the timeout allowed for a single
     * forwarding hop toward one backend. The bound deliberately does not cover health checks,
     * which are served on a separate path.
     *
     * <p>The deployment provider calls this at provision time and whenever the replica set or the
     * function tuning changes, so a proxy must be able to adopt new values for the whole of its
     * life. The default is a no-op so that fixed-configuration proxies and test doubles are not
     * forced to implement it.
     */
    default void updateLimits(int maxInFlight, Duration singleHopTimeout) {
    }

    default void enablePhysicalSlots() { throw new UnsupportedOperationException("Physical replica slots unsupported"); }
    default boolean backendReady(String backendId) { return true; }
    default void beginDrain(String backendId) { }
    default boolean awaitDrained(String backendId, Duration timeout) { return true; }

    @Override
    void close();
}
