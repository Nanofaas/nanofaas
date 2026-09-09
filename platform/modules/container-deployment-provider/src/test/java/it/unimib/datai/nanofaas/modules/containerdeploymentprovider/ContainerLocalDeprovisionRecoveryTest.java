package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Recoverability of a partial deprovision (plan task P08, review finding R6, invariant I10).
 *
 * <p>Every case here uses a controlled runtime adapter rather than a container engine: the
 * behaviour under test is what the provider does when a removal, a discovery or a proxy close
 * fails, which a real engine cannot be asked for on demand. The Docker-backed counterpart belongs
 * to NanoLab.
 */
class ContainerLocalDeprovisionRecoveryTest {

    @Test
    void oneFailingReplicaStillRemovesTheOthersAndKeepsOnlyTheSurvivorTracked() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 3));

        adapter.failRemovalOf("nanofaas-echo-r2");

        PartialDeprovisionException partial = (PartialDeprovisionException)
                catchThrowable(() -> provider.deprovision("echo"));

        assertThat(partial).isNotNull();
        // Both healthy replicas were attempted despite the failure in the middle of the pass.
        assertThat(adapter.removed()).containsExactly("nanofaas-echo-r3", "nanofaas-echo-r1");
        assertThat(partial.remainingResources()).containsExactly("nanofaas-echo-r2");
        assertThat(partial.functionName()).isEqualTo("echo");
        assertThat(partial.backendId()).isEqualTo("container-local");
        assertThat(partial).hasRootCauseMessage("remove of nanofaas-echo-r2 failed");
        // The one resource that survived is the one that is still tracked: nothing else lingers,
        // and nothing that is gone is still claimed.
        assertThat(trackedContainers(provider, "echo")).containsExactly("nanofaas-echo-r2");
        assertThat(proxy.closes()).isEqualTo(1);
    }

    @Test
    void aSecondAttemptResumesTheCleanupAndLeavesNothingBehind() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 2));
        adapter.failRemovalOf("nanofaas-echo-r1");
        assertThatThrownBy(() -> provider.deprovision("echo")).isInstanceOf(PartialDeprovisionException.class);

        adapter.recover();
        provider.deprovision("echo");

        // The retry removes only what was left: r2 is not deleted twice.
        assertThat(adapter.removed()).containsExactly("nanofaas-echo-r2", "nanofaas-echo-r1");
        assertThat(trackedFunctions(provider)).isEmpty();
        assertThat(trackedLocks(provider)).isEmpty();
        assertThat(proxy.closes()).isEqualTo(2);
    }

    @Test
    void aFailingProxyCloseIsReportedAndTheRetryClosesItForGood() {
        SteerableAdapter adapter = new SteerableAdapter();
        FailingCloseProxy proxy = new FailingCloseProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 2));

        PartialDeprovisionException partial = (PartialDeprovisionException)
                catchThrowable(() -> provider.deprovision("echo"));

        assertThat(partial).isNotNull();
        assertThat(partial.remainingResources()).containsExactly("local invocation proxy");
        // A proxy that will not close never blocks the containers from being removed.
        assertThat(adapter.removed()).containsExactly("nanofaas-echo-r2", "nanofaas-echo-r1");
        assertThat(trackedFunctions(provider)).containsExactly("echo");
        assertThat(trackedContainers(provider, "echo")).isEmpty();

        proxy.stopFailing();
        provider.deprovision("echo");

        assertThat(proxy.closes()).isEqualTo(2);
        assertThat(trackedFunctions(provider)).isEmpty();
    }

    @Test
    void aFailedDiscoveryDoesNotStopTheRemovalOfWhatIsTracked() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 2));

        adapter.failDiscovery();

        PartialDeprovisionException partial = (PartialDeprovisionException)
                catchThrowable(() -> provider.deprovision("echo"));

        assertThat(partial).isNotNull();
        assertThat(adapter.removed()).containsExactly("nanofaas-echo-r2", "nanofaas-echo-r1");
        assertThat(partial.remainingResources())
                .containsExactly("managed containers of 'echo' (could not be listed)");
        // Tracking survives even though it is now empty: only a successful pass gives up ownership.
        assertThat(trackedFunctions(provider)).containsExactly("echo");
    }

    @Test
    void aNewProviderRediscoversAndRemovesTheContainersLeftByTheOldOne() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy firstProxy = new RecordingProxy();
        ContainerLocalDeploymentProvider first = provider(adapter, firstProxy);
        first.provision(spec("echo", 2));
        adapter.failRemovalOf("nanofaas-echo-r1");
        assertThatThrownBy(() -> first.deprovision("echo")).isInstanceOf(PartialDeprovisionException.class);

        // Context shutdown: the Java-side resources go, the surviving container does not.
        first.close();
        assertThat(firstProxy.closes()).isEqualTo(2);
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1");

        // Restart: a provider with no state at all finds the leftover by its managed labels.
        adapter.recover();
        RecordingProxy secondProxy = new RecordingProxy();
        ContainerLocalDeploymentProvider restarted = provider(adapter, secondProxy);
        restarted.deprovision("echo");

        assertThat(adapter.live()).isEmpty();
        assertThat(trackedFunctions(restarted)).isEmpty();
        // A restarted provider owns no proxy for a function it never provisioned.
        assertThat(secondProxy.closes()).isZero();
    }

    @Test
    void contextShutdownClosesTheProxiesAndKeepsTheContainersRecoverable() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 2));

        provider.close();

        assertThat(proxy.closes()).isEqualTo(1);
        assertThat(adapter.removed()).isEmpty();
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
    }

    @Test
    void aFunctionInPendingRemovalIsNeitherReprovisionedNorScaled() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 1));
        adapter.failRemovalOf("nanofaas-echo-r1");
        assertThatThrownBy(() -> provider.deprovision("echo")).isInstanceOf(PartialDeprovisionException.class);

        FunctionSpec sameName = spec("echo", 1);
        assertThatThrownBy(() -> provider.provision(sameName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending removal");

        provider.setReplicas("echo", 4);
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1");
        assertThat(trackedContainers(provider, "echo")).containsExactly("nanofaas-echo-r1");
    }

    @Test
    void aScaleDownWhoseRemovalFailsKeepsTheReplicaTracked() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 2));

        adapter.failRemovalOf("nanofaas-echo-r2");

        assertThatThrownBy(() -> provider.setReplicas("echo", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("remove of nanofaas-echo-r2 failed");
        // The container is still running, so the provider still claims it: a map entry dropped
        // before the removal is confirmed is a container nothing can find again.
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
        assertThat(trackedContainers(provider, "echo"))
                .containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
    }

    @Test
    void reconcileRebuildsAPendingRemovalIntoAVerifiedDeployment() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy firstProxy = new RecordingProxy();
        RecordingProxy rebuiltProxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, firstProxy, rebuiltProxy);
        provider.provision(spec("echo", 1));
        adapter.failRemovalOf("nanofaas-echo-r1");
        assertThatThrownBy(() -> provider.deprovision("echo")).isInstanceOf(PartialDeprovisionException.class);
        adapter.recover();

        // The one path allowed to declare the deployment operational again: it rebuilds from the
        // resources that are actually there and probes them before publishing an endpoint.
        ProvisionResult rebuilt = provider.reconcile(spec("echo", 1), 1,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));

        assertThat(rebuilt.endpointUrl()).isEqualTo("http://127.0.0.1:19090/invoke");
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1");
        assertThat(trackedContainers(provider, "echo")).containsExactly("nanofaas-echo-r1");
        assertThat(rebuiltProxy.closes()).isZero();
        // The pending state is gone with the proxy it owned, so the function serves again.
        assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(1);
        provider.setReplicas("echo", 2);
        assertThat(adapter.live()).containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
    }

    @Test
    void aCleanDeprovisionSweepsAContainerWhoseTrackingWasLostWhileItWasCreated() {
        SteerableAdapter adapter = new SteerableAdapter();
        RecordingProxy proxy = new RecordingProxy();
        ContainerLocalDeploymentProvider provider = provider(adapter, proxy);
        provider.provision(spec("echo", 1));
        // A container the provider never got to track: exactly what a crashed create leaves behind.
        adapter.runContainer(instanceSpec("nanofaas-echo-r2"));

        provider.deprovision("echo");

        assertThat(adapter.live()).isEmpty();
        assertThat(adapter.removed()).containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
        assertThat(trackedFunctions(provider)).isEmpty();
    }

    // --- fixtures ---------------------------------------------------------------------------

    /** Hands out the given proxies in order, repeating the last one once they run out. */
    private static ContainerLocalDeploymentProvider provider(ContainerRuntimeAdapter adapter,
                                                             ManagedFunctionProxy... proxies) {
        AtomicInteger created = new AtomicInteger();
        return new ContainerLocalDeploymentProvider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1",
                        Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new AlwaysReadyProbe(),
                new SequentialPortAllocator(),
                functionName -> proxies[Math.min(created.getAndIncrement(), proxies.length - 1)]);
    }

    private static FunctionSpec spec(String name, int minReplicas) {
        return new FunctionSpec(name, "img:latest", List.of(), new LinkedHashMap<>(), null,
                30_000, 4, 100, 3, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, minReplicas, 5, List.of()));
    }

    private static ContainerInstanceSpec instanceSpec(String containerName) {
        return new ContainerInstanceSpec(containerName, "img:latest", 31999, List.of(), Map.of(), null,
                Map.of(ContainerLocalDeploymentProvider.MANAGED_LABEL, "true",
                        ContainerLocalDeploymentProvider.FUNCTION_LABEL, "echo",
                        ContainerLocalDeploymentProvider.REPLICA_LABEL, "2"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> states(ContainerLocalDeploymentProvider provider) {
        try {
            Field states = ContainerLocalDeploymentProvider.class.getDeclaredField("states");
            states.setAccessible(true);
            return (Map<String, Object>) states.get(provider);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Set<String> trackedFunctions(ContainerLocalDeploymentProvider provider) {
        return states(provider).keySet();
    }

    private static Set<String> trackedLocks(ContainerLocalDeploymentProvider provider) {
        try {
            Field locks = ContainerLocalDeploymentProvider.class.getDeclaredField("locks");
            locks.setAccessible(true);
            return ((Map<String, ?>) locks.get(provider)).keySet();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The container names the provider still claims for a function, in tracking order. */
    private static List<String> trackedContainers(ContainerLocalDeploymentProvider provider, String functionName) {
        Object state = states(provider).get(functionName);
        if (state == null) {
            return List.of();
        }
        try {
            Field replicas = state.getClass().getDeclaredField("replicas");
            replicas.setAccessible(true);
            List<String> names = new ArrayList<>();
            for (Object replica : ((Map<?, ?>) replicas.get(state)).values()) {
                Field containerName = replica.getClass().getDeclaredField("containerName");
                containerName.setAccessible(true);
                names.add((String) containerName.get(replica));
            }
            return names;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A runtime adapter that keeps a real inventory of live containers, so "what is left" is
     * observed instead of assumed, and whose removal and discovery can be made to fail on demand.
     */
    private static final class SteerableAdapter implements ContainerRuntimeAdapter {
        private final List<String> live = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private String failingContainer;
        private boolean discoveryFails;

        void failRemovalOf(String containerName) {
            this.failingContainer = containerName;
        }

        void failDiscovery() {
            this.discoveryFails = true;
        }

        void recover() {
            this.failingContainer = null;
            this.discoveryFails = false;
        }

        List<String> live() {
            return List.copyOf(live);
        }

        List<String> removed() {
            return List.copyOf(removed);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void pullImage(String image) {
            // Images are assumed present.
        }

        @Override
        public void runContainer(ContainerInstanceSpec spec) {
            live.add(spec.containerName());
        }

        @Override
        public void removeContainer(String containerName) {
            if (containerName.equals(failingContainer)) {
                throw new IllegalStateException("remove of " + containerName + " failed");
            }
            removed.add(containerName);
            live.remove(containerName);
        }

        @Override
        public List<ManagedContainer> listManagedContainers(String functionName) {
            if (discoveryFails) {
                throw new IllegalStateException("docker ps failed");
            }
            String prefix = "nanofaas-" + functionName.toLowerCase();
            return live.stream()
                    .filter(name -> name.startsWith(prefix + "-r"))
                    .map(name -> new ManagedContainer(name,
                            ContainerLocalDeploymentProvider.replicaIndex(name), 31000, true))
                    .toList();
        }
    }

    private static class RecordingProxy implements ManagedFunctionProxy {
        private int closes;

        int closes() {
            return closes;
        }

        @Override
        public String endpointUrl() {
            return "http://127.0.0.1:19090/invoke";
        }

        @Override
        public void updateBackends(List<String> backendBaseUrls) {
            // Not part of what these cases observe.
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class FailingCloseProxy extends RecordingProxy {
        private boolean failing = true;

        void stopFailing() {
            this.failing = false;
        }

        @Override
        public void close() {
            super.close();
            if (failing) {
                throw new IllegalStateException("proxy close failed");
            }
        }
    }

    private static final class AlwaysReadyProbe implements EndpointProbe {
        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            // Deterministic: readiness is not what these cases exercise.
        }

        @Override
        public boolean isReady(String baseUrl) {
            return true;
        }
    }

    private static final class SequentialPortAllocator implements PortAllocator {
        private int next = 31001;

        @Override
        public int nextPort() {
            return next++;
        }
    }
}
