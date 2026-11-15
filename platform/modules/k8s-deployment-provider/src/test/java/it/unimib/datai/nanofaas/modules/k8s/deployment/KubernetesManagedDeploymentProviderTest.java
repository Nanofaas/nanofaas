package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Pods are simulated by the EndpointSlices of the function's Service. The provider is deliberately
 * not closed after each test: closing the watch from the client side races the mock server (see
 * EndpointSliceWatcherTest).
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesManagedDeploymentProviderTest {
    KubernetesClient client;

    private KubernetesManagedDeploymentProvider provider;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clients = mock(ObjectProvider.class);
        when(clients.getObject()).thenReturn(client);
        var resourceManager = new KubernetesResourceManager(clients, new KubernetesProperties("default", null));
        provider = new KubernetesManagedDeploymentProvider(resourceManager, clients);
    }

    @Test
    void dispatchGoesToTheReadyPodsInTurn() {
        var result = provider.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"), ready("10.0.0.2"));
        awaitBackends("echo", 2);

        var endpoints = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            ManagedInvocationTarget target = acquire("echo", result, "e" + i).target();
            endpoints.add(target.endpointUrl());
            target.close();
        }

        assertThat(endpoints).containsExactly(
                "http://10.0.0.1:8080/invoke", "http://10.0.0.2:8080/invoke",
                "http://10.0.0.1:8080/invoke", "http://10.0.0.2:8080/invoke");
    }

    @Test
    void noReadyPodMeansUnavailable() {
        var result = provider.provision(spec("echo", 4));

        assertThat(acquire("echo", result, "e1")).isEqualTo(ManagedTargetResolution.UNAVAILABLE);
        assertThat(provider.acquireInvocationTarget("never-registered", Map.of(), "e2", "1"))
                .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
    }

    @Test
    void aPodThatStartsTerminatingReceivesNoNewWork() {
        var result = provider.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"), ready("10.0.0.2"));
        awaitBackends("echo", 2);

        pods("fn-echo", ready("10.0.0.1"), terminating("10.0.0.2"));
        awaitBackends("echo", 1);

        for (int i = 0; i < 3; i++) {
            var target = acquire("echo", result, "e" + i).target();
            assertThat(target.endpointUrl()).isEqualTo("http://10.0.0.1:8080/invoke");
            target.close();
        }
    }

    @Test
    void aFullPoolRefuses() {
        var result = provider.provision(spec("echo", 1));
        pods("fn-echo", ready("10.0.0.1"));
        awaitBackends("echo", 1);

        var held = acquire("echo", result, "e1");

        assertThat(held.target()).isNotNull();
        assertThat(acquire("echo", result, "e2")).isEqualTo(ManagedTargetResolution.UNAVAILABLE);
        held.target().close();
    }

    @Test
    void updateSpecReappliesThePerPodLimit() {
        var result = provider.provision(spec("echo", 1));
        pods("fn-echo", ready("10.0.0.1"));
        awaitBackends("echo", 1);
        var held = acquire("echo", result, "e1");
        assertThat(acquire("echo", result, "e2")).isEqualTo(ManagedTargetResolution.UNAVAILABLE);

        provider.updateSpec(spec("echo", 2));

        var second = acquire("echo", result, "e3");
        assertThat(second.target()).isNotNull();
        held.target().close();
        second.target().close();
    }

    @Test
    void deletingTheLastSliceMakesDispatchUnavailable() {
        var result = provider.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"));
        awaitBackends("echo", 1);

        client.discovery().v1().endpointSlices().inNamespace("default").withName("fn-echo-slice").delete();

        awaitEquals(() -> acquire("echo", result, "e1"), ManagedTargetResolution.UNAVAILABLE);
        assertThat(provider.getReplicaStatus("echo").readyReplicas()).isZero();
    }

    @Test
    void readyReplicasCountPodsTheDispatchCanReach() {
        provider.provision(spec("echo", 4, 2));
        markDeploymentReady("fn-echo", 2);

        // The Deployment says ready, but no pod has reached the control plane yet: the wake-up gate
        // must keep waiting rather than dispatch into an empty pool.
        assertThat(provider.getReplicaStatus("echo")).isEqualTo(new ReplicaStatus(2, 0));

        pods("fn-echo", ready("10.0.0.1"), ready("10.0.0.2"));
        awaitEquals(() -> provider.getReplicaStatus("echo"), new ReplicaStatus(2, 2));

        pods("fn-echo", ready("10.0.0.1"), ready("10.0.0.2"), ready("10.0.0.3"));   // surge
        awaitDispatchTo("echo", "http://10.0.0.3:8080/invoke");
        assertThat(provider.getReplicaStatus("echo")).isEqualTo(new ReplicaStatus(2, 2));
        assertThat(provider.getReadyReplicas("echo")).isEqualTo(2);
    }

    /**
     * Scaled to zero and straight back to one: the old pod is terminating and the Deployment no
     * longer counts it, but its endpoint is not marked yet. Counting it would let the wake-up gate
     * release the first dispatch into a dying pod.
     */
    @Test
    void aPodTheDeploymentNoLongerCountsIsNotReadyWhileItsSliceLags() {
        provider.provision(spec("echo", 4, 1));
        pods("fn-echo", ready("10.0.0.1"));
        markDeploymentReady("fn-echo", 1);
        awaitEquals(() -> provider.getReplicaStatus("echo"), new ReplicaStatus(1, 1));

        markDeploymentReady("fn-echo", 0);

        assertThat(provider.getReplicaStatus("echo")).isEqualTo(new ReplicaStatus(1, 0));
    }

    @Test
    void reconcileAdoptsBackendsAlreadyInTheStore() {
        pods("fn-echo", ready("10.0.0.1"));

        var result = provider.reconcile(spec("echo", 4), 1, objects("default"));

        // No waiting: the slices were listed when the watcher started, before the function existed.
        var target = acquire("echo", result, "e1").target();
        assertThat(target).isNotNull();
        assertThat(target.endpointUrl()).isEqualTo("http://10.0.0.1:8080/invoke");
    }

    /**
     * Two changes of the same slice in quick succession end with the pool on the second: refreshes
     * read the store when they apply, never an event's payload. Informer events are handled one at
     * a time, so this does not exercise refreshes applied out of order.
     */
    @Test
    void theLastOfTwoQuickSliceChangesWins() {
        var result = provider.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"));
        pods("fn-echo", ready("10.0.0.2"));

        awaitDispatchTo("echo", "http://10.0.0.2:8080/invoke");
        for (int i = 0; i < 4; i++) {
            var target = acquire("echo", result, "e" + i).target();
            assertThat(target.endpointUrl()).isEqualTo("http://10.0.0.2:8080/invoke");
            target.close();
        }
    }

    @Test
    void deprovisionStopsDispatch() {
        var result = provider.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"));
        awaitBackends("echo", 1);

        provider.deprovision("echo");

        assertThat(acquire("echo", result, "e1")).isEqualTo(ManagedTargetResolution.UNAVAILABLE);
        assertThat(client.apps().deployments().inNamespace("default").withName("fn-echo").get()).isNull();
    }

    /**
     * FunctionService restores a function whose removal failed without reconciling it: the pods are
     * still there, so dispatch to them must still work.
     */
    @Test
    void aFailedDeprovisionKeepsTheFunctionDispatchable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clients = mock(ObjectProvider.class);
        when(clients.getObject()).thenReturn(client);
        var resourceManager = spy(new KubernetesResourceManager(clients, new KubernetesProperties("default", null)));
        doThrow(new KubernetesClientException("delete refused")).when(resourceManager).deprovision("echo");
        var failing = new KubernetesManagedDeploymentProvider(resourceManager, clients);
        var result = failing.provision(spec("echo", 4));
        pods("fn-echo", ready("10.0.0.1"));
        markDeploymentReady("fn-echo", 3);
        awaitEquals(() -> failing.getReplicaStatus("echo").readyReplicas(), 1);

        assertThatThrownBy(() -> failing.deprovision("echo")).isInstanceOf(KubernetesClientException.class);

        var target = failing.acquireInvocationTarget("echo", result.deploymentObjects(), "e1", "1").target();
        assertThat(target).isNotNull();
        assertThat(target.endpointUrl()).isEqualTo("http://10.0.0.1:8080/invoke");
        assertThat(failing.getReplicaStatus("echo").readyReplicas()).isEqualTo(1);
    }

    /** The watcher is started before anything is created, so a watcher that cannot start leaves nothing behind. */
    @Test
    void aWatcherThatCannotStartLeavesNoResources() {
        var unstartable = providerWhoseWatcherCannotStart();

        assertThatThrownBy(() -> unstartable.provision(spec("echo", 4))).isInstanceOf(KubernetesClientException.class);

        assertThat(client.apps().deployments().inNamespace("default").withName("fn-echo").get()).isNull();
        assertThat(client.services().inNamespace("default").withName("fn-echo").get()).isNull();
    }

    @Test
    void aWatcherThatCannotStartLeavesReconcileUntouched() {
        client.services().inNamespace("default").resource(new io.fabric8.kubernetes.api.model.ServiceBuilder()
                .withNewMetadata().withName("fn-echo").endMetadata()
                .withNewSpec().withClusterIP("10.43.0.9").addToSelector("function", "echo").endSpec().build()).create();
        var unstartable = providerWhoseWatcherCannotStart();

        assertThatThrownBy(() -> unstartable.reconcile(spec("echo", 4), 1, objects("default")))
                .isInstanceOf(KubernetesClientException.class);

        assertThat(client.services().inNamespace("default").withName("fn-echo").get().getSpec().getClusterIP())
                .isEqualTo("10.43.0.9");
        assertThat(client.apps().deployments().inNamespace("default").withName("fn-echo").get()).isNull();
    }

    private KubernetesManagedDeploymentProvider providerWhoseWatcherCannotStart() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clients = mock(ObjectProvider.class);
        when(clients.getObject()).thenReturn(client);
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> forbidden = mock(ObjectProvider.class);
        when(forbidden.getObject()).thenThrow(new KubernetesClientException("endpointslices is forbidden"));
        return new KubernetesManagedDeploymentProvider(
                new KubernetesResourceManager(clients, new KubernetesProperties("default", null)), forbidden);
    }

    @Test
    void provisionNoLongerPublishesAServiceUrl() {
        ProvisionResult result = provider.provision(spec("word-stats", 4));

        assertThat(result.endpointUrl()).isNull();
        assertThat(result.backendId()).isEqualTo("k8s");
        assertThat(result.deploymentObjects()).containsOnly(
                entry(ProvisionResult.DEPLOYMENT, "fn-word-stats"),
                entry(ProvisionResult.SERVICE, "fn-word-stats"),
                entry(ProvisionResult.NAMESPACE, "default"));
    }

    @Test
    void reconcileScalesToThePersistedReplicaTarget() {
        provider.reconcile(spec("echo", 4), 3, objects("default"));

        Deployment deployment = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertThat(deployment.getSpec().getReplicas()).isEqualTo(3);
    }

    @Test
    void reportsTheK8sBackendForDeploymentFunctions() {
        var spec = spec("echo", 4);

        assertThat(provider.backendId()).isEqualTo("k8s");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.supports(spec)).isTrue();
    }

    private ManagedTargetResolution acquire(String function, ProvisionResult result, String executionId) {
        return provider.acquireInvocationTarget(function, result.deploymentObjects(), executionId, "1");
    }

    /**
     * Desired is 3 in these specs and the Deployment is marked fully ready, so the ready count shows
     * the pods the pool can reach, up to three.
     */
    private void awaitBackends(String function, int ready) {
        markDeploymentReady("fn-" + function, 3);
        awaitEquals(() -> provider.getReplicaStatus(function).readyReplicas(), ready);
    }

    /** Every acquire moves the pool's turn, so this checks the very answer it stopped waiting on. */
    private void awaitDispatchTo(String function, String endpoint) {
        var objects = objects("default");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String last = null;
        while (!endpoint.equals(last) && System.nanoTime() < deadline) {
            var target = provider.acquireInvocationTarget(function, objects, "probe", "1").target();
            last = target == null ? null : target.endpointUrl();
            if (target != null) target.close();
        }
        assertThat(last).isEqualTo(endpoint);
    }

    private static <T> void awaitEquals(Supplier<T> actual, T expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!expected.equals(actual.get()) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(actual.get()).isEqualTo(expected);
    }

    private void pods(String service, Endpoint... endpoints) {
        var slice = new EndpointSliceBuilder()
                .withNewMetadata().withName(service + "-slice").addToLabels("kubernetes.io/service-name", service).endMetadata()
                .withAddressType("IPv4")
                .withEndpoints(endpoints)
                .build();
        client.discovery().v1().endpointSlices().inNamespace("default").resource(slice).createOr(existing -> existing.update());
    }

    private void markDeploymentReady(String name, int ready) {
        var deployment = client.apps().deployments().inNamespace("default").withName(name).get();
        client.apps().deployments().inNamespace("default").resource(new DeploymentBuilder(deployment)
                .editOrNewStatus().withReadyReplicas(ready)
                .withObservedGeneration(deployment.getMetadata().getGeneration()).endStatus()
                .build()).updateStatus();
    }

    private static Endpoint ready(String address) {
        return new EndpointBuilder().withAddresses(address).withNewConditions().withReady(true).endConditions().build();
    }

    private static Endpoint terminating(String address) {
        return new EndpointBuilder().withAddresses(address)
                .withNewConditions().withReady(false).withTerminating(true).endConditions().build();
    }

    private static Map<String, String> objects(String namespace) {
        return Map.of(ProvisionResult.NAMESPACE, namespace,
                ProvisionResult.DEPLOYMENT, "fn-echo",
                ProvisionResult.SERVICE, "fn-echo");
    }

    private static FunctionSpec spec(String name, int concurrency) {
        return spec(name, concurrency, 3);
    }

    private static FunctionSpec spec(String name, int concurrency, int minReplicas) {
        return new FunctionSpec(name, "img:latest", List.of(), Map.of(), null, 30_000, concurrency, 100, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, minReplicas, 10, List.of()));
    }
}
