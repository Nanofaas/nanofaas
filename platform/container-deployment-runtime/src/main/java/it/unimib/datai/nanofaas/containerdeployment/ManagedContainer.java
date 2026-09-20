package it.unimib.datai.nanofaas.containerdeployment;

/**
 * Runtime-owned instance and its endpoint as seen by the control plane.
 *
 * @param baseUrl nonblank reachable endpoint after a successful start; discovery may return null
 *                when the instance is unaddressable
 * @param running true only for running instances eligible for adoption; false while removal is pending
 */
public record ManagedContainer(String name, int replicaIndex, String baseUrl, boolean running) {
}
