package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Follows the EndpointSlices of the function Services in one namespace and answers which pods of a
 * Service are ready. One informer serves every function: a watch per function would open one API
 * server connection each.
 */
final class EndpointSliceWatcher implements AutoCloseable {
    static final String SERVICE_NAME_LABEL = "kubernetes.io/service-name";
    // Periodic resync re-reports every slice, so a change missed while the watch reconnected heals.
    private static final long RESYNC_MILLIS = 30_000;
    private static final int FUNCTION_PORT = 8080;

    private final KubernetesClient client;
    private final String namespace;
    private final Consumer<String> changedService;
    private volatile SharedIndexInformer<EndpointSlice> informer;

    EndpointSliceWatcher(KubernetesClient client, String namespace, Consumer<String> changedService) {
        this.client = Objects.requireNonNull(client);
        this.namespace = Objects.requireNonNull(namespace);
        this.changedService = Objects.requireNonNull(changedService);
    }

    /** Starts following the slices; returns once the first listing is in the store. Idempotent. */
    synchronized void start() {
        if (informer != null) return;
        informer = client.discovery().v1().endpointSlices().inNamespace(namespace)
                .withLabel(SERVICE_NAME_LABEL)
                .inform(new ResourceEventHandler<>() {
                    @Override public void onAdd(EndpointSlice slice) { report(slice); }
                    @Override public void onUpdate(EndpointSlice previous, EndpointSlice slice) { report(slice); }
                    @Override public void onDelete(EndpointSlice slice, boolean finalStateUnknown) { report(slice); }
                }, RESYNC_MILLIS);
    }

    /**
     * The ready pods of a Service, across all its slices: {@code http://<address>:8080} for every
     * endpoint whose ready condition is not false (absent means unknown, which Kubernetes says to
     * read as ready) and that is not terminating. Sorted, without duplicates.
     */
    // ponytail: scans every slice of the namespace per call; index the store by service if functions number in the thousands.
    List<String> readyBackends(String serviceName) {
        var current = informer;
        if (current == null) return List.of();
        var backends = new TreeSet<String>();
        for (EndpointSlice slice : current.getStore().list()) {
            if (!serviceName.equals(serviceOf(slice)) || slice.getEndpoints() == null) continue;
            boolean ipv6 = "IPv6".equals(slice.getAddressType());
            for (Endpoint endpoint : slice.getEndpoints()) {
                if (!admissible(endpoint) || endpoint.getAddresses() == null) continue;
                for (String address : endpoint.getAddresses()) {
                    backends.add("http://" + (ipv6 ? "[" + address + "]" : address) + ":" + FUNCTION_PORT);
                }
            }
        }
        return List.copyOf(backends);
    }

    @Override
    public synchronized void close() {
        if (informer != null) informer.close();
        informer = null;
    }

    private void report(EndpointSlice slice) {
        String service = serviceOf(slice);
        if (service != null) changedService.accept(service);
    }

    private static boolean admissible(Endpoint endpoint) {
        var conditions = endpoint.getConditions();
        if (conditions == null) return true;
        return !Boolean.FALSE.equals(conditions.getReady()) && !Boolean.TRUE.equals(conditions.getTerminating());
    }

    private static String serviceOf(EndpointSlice slice) {
        var labels = slice.getMetadata() == null ? null : slice.getMetadata().getLabels();
        return labels == null ? null : labels.get(SERVICE_NAME_LABEL);
    }
}
