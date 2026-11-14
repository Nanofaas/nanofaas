package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watcher is deliberately not closed after each test. Closing a watch from the client side races
 * the mock server: its WatchEventsListener waits on the event loop for an executor whose queued
 * close needs that same event loop, which stalls the server's shutdown. The extension shuts the
 * server down first and closes the client after, which avoids it.
 */
@EnableKubernetesMockClient(crud = true)
class EndpointSliceWatcherTest {
    KubernetesClient client;

    private final LinkedBlockingQueue<String> changes = new LinkedBlockingQueue<>();
    private EndpointSliceWatcher watcher;

    @Test
    void readyBackendsFollowEndpointConditions() {
        // Kubernetes: an absent ready condition is unknown and is read as ready; terminating never is.
        create(slice("fn-echo-a", "fn-echo", "IPv4",
                endpoint("10.0.0.1", true, null),
                endpoint("10.0.0.2", false, null),
                endpoint("10.0.0.3", null, null),
                endpoint("10.0.0.4", true, true)));

        start();

        assertThat(watcher.readyBackends("fn-echo")).containsExactly("http://10.0.0.1:8080", "http://10.0.0.3:8080");
    }

    @Test
    void backendsOfAServiceAreMergedAcrossSlices() {
        create(slice("fn-echo-a", "fn-echo", "IPv4", endpoint("10.0.0.1", true, null)));
        create(slice("fn-echo-b", "fn-echo", "IPv4", endpoint("10.0.0.2", true, null), endpoint("10.0.0.1", true, null)));

        start();

        assertThat(watcher.readyBackends("fn-echo")).containsExactly("http://10.0.0.1:8080", "http://10.0.0.2:8080");
    }

    @Test
    void ipv6AddressesAreBracketed() {
        create(slice("fn-echo-a", "fn-echo", "IPv6", endpoint("fd00::5", true, null)));

        start();

        assertThat(watcher.readyBackends("fn-echo")).containsExactly("http://[fd00::5]:8080");
    }

    @Test
    void changesAreReportedByServiceName() throws Exception {
        start();

        create(slice("fn-echo-a", "fn-echo", "IPv4", endpoint("10.0.0.1", true, null)));
        assertThat(changes.poll(5, TimeUnit.SECONDS)).isEqualTo("fn-echo");
        awaitBackends("fn-echo", List.of("http://10.0.0.1:8080"));

        changes.clear();
        create(slice("fn-echo-a", "fn-echo", "IPv4", endpoint("10.0.0.1", true, null), endpoint("10.0.0.2", true, null)));
        assertThat(changes.poll(5, TimeUnit.SECONDS)).isEqualTo("fn-echo");
        awaitBackends("fn-echo", List.of("http://10.0.0.1:8080", "http://10.0.0.2:8080"));

        changes.clear();
        client.discovery().v1().endpointSlices().inNamespace("default").withName("fn-echo-a").delete();
        assertThat(changes.poll(5, TimeUnit.SECONDS)).isEqualTo("fn-echo");
    }

    @Test
    void deletingTheLastSliceLeavesNoBackends() {
        create(slice("fn-echo-a", "fn-echo", "IPv4", endpoint("10.0.0.1", true, null)));
        start();
        assertThat(watcher.readyBackends("fn-echo")).containsExactly("http://10.0.0.1:8080");

        client.discovery().v1().endpointSlices().inNamespace("default").withName("fn-echo-a").delete();

        awaitBackends("fn-echo", List.of());
    }

    @Test
    void slicesOfAnotherServiceWithACommonPrefixAreIgnored() {
        create(slice("fn-a-b-x", "fn-a-b", "IPv4", endpoint("10.0.0.7", true, null)));

        start();

        assertThat(watcher.readyBackends("fn-a")).isEmpty();
        assertThat(watcher.readyBackends("fn-a-b")).containsExactly("http://10.0.0.7:8080");
    }

    private void start() {
        watcher = new EndpointSliceWatcher(client, "default", changes::add);
        watcher.start();
    }

    private void create(EndpointSlice slice) {
        client.discovery().v1().endpointSlices().inNamespace("default").resource(slice).createOr(existing -> existing.update());
    }

    private void awaitBackends(String service, List<String> expected) {
        awaitEquals(() -> watcher.readyBackends(service), expected);
    }

    private static <T> void awaitEquals(Supplier<T> actual, T expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!expected.equals(actual.get()) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(actual.get()).isEqualTo(expected);
    }

    private static EndpointSlice slice(String name, String service, String addressType, Endpoint... endpoints) {
        return new EndpointSliceBuilder()
                .withNewMetadata().withName(name).addToLabels("kubernetes.io/service-name", service).endMetadata()
                .withAddressType(addressType)
                .withEndpoints(endpoints)
                .build();
    }

    private static Endpoint endpoint(String address, Boolean ready, Boolean terminating) {
        return new EndpointBuilder()
                .withAddresses(address)
                .withNewConditions().withReady(ready).withTerminating(terminating).endConditions()
                .build();
    }
}
