package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxy;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for finding R6 of the 2026-09-08 pre-soak review.
 *
 * <p>{@link ContainerLocalDeploymentProvider#deprovision} removes the function's state
 * from {@code states} before deleting replicas and closes the proxy only after every
 * deletion succeeds. When {@code adapter.removeContainer} throws, the state is already
 * gone and {@code safeClose(state.proxy)} is skipped: the proxy — which owns a running
 * HttpServer, an executor and an HttpClient — is dropped without being closed, and no
 * reference to it remains for a later retry.
 *
 * <p>Correct behavior (plan task P08, invariant I10): a partial deprovision failure must
 * not lose the provider's only handle on the resources. Either the proxy is closed
 * despite the removal failure, or the state stays tracked so a retry can still clean it
 * up. A subsequent successful deprovision must then leave no proxy open.
 *
 * <p>This test injects an adapter failure and asserts the desired behavior. It was RED on the
 * baseline before P08 (the proxy was left open and untracked, and the assertion below on the
 * resources still being reachable failed); P08 makes it pass by closing the proxy on a guaranteed
 * path, keeping the tracked state until nothing is left, and reporting the explicit
 * {@link PartialDeprovisionException} outcome.
 */
class R6DeprovisionFailureOwnershipRegressionTest {

    private static final class FailingRemovalAdapter implements ContainerRuntimeAdapter {
        final AtomicBoolean failRemoval = new AtomicBoolean();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void pullImage(String image) {
        }

        @Override
        public ManagedContainer runContainer(ContainerInstanceSpec spec) {
            return new ManagedContainer(spec.containerName(), 1, "http://127.0.0.1:19001", true);
        }

        @Override
        public void removeContainer(String containerName) {
            if (failRemoval.get()) {
                throw new IllegalStateException("Docker unavailable");
            }
        }

        @Override
        public List<ManagedContainer> listManagedContainers(String functionName) {
            return List.of();
        }
    }

    private static final class RecordingProxy implements ManagedFunctionProxy {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public String endpointUrl() {
            return "http://127.0.0.1:19001/invoke";
        }

        @Override
        public void updateBackends(List<String> backendBaseUrls) {
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null, null);
    }

    private static int trackedStates(ContainerLocalDeploymentProvider provider) throws Exception {
        Field states = it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider.class.getDeclaredField("states");
        states.setAccessible(true);
        return ((Map<?, ?>) states.get(provider)).size();
    }

    @Test
    void failedDeprovisionDoesNotLeakAnUntrackedProxy() throws Exception {
        FailingRemovalAdapter adapter = new FailingRemovalAdapter();
        RecordingProxy proxy = new RecordingProxy();
        RoundRobinFunctionProxyFactory proxyFactory = mock(RoundRobinFunctionProxyFactory.class);
        when(proxyFactory.create(anyString())).thenReturn(proxy);
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1",
                        Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new EndpointProbe() {
                    @Override
                    public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
                    }

                    @Override
                    public boolean isReady(String baseUrl) {
                        return true;
                    }
                },
                proxyFactory);

        provider.provision(spec("fn"));

        adapter.failRemoval.set(true);
        assertThatThrownBy(() -> provider.deprovision("fn"))
                .as("the removal failure must propagate, as the explicit partial outcome")
                .isInstanceOf(PartialDeprovisionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Docker unavailable");

        // Immediately after the failure the resources must still be reachable: either the
        // proxy was already closed, or the provider still tracks the state for a retry.
        boolean resourcesStillReachable = proxy.closed.get() || trackedStates(provider) == 1;
        assertThat(resourcesStillReachable)
                .as("a failed deprovision must not drop the proxy without closing or tracking it")
                .isTrue();

        // A second deprovision once the adapter recovers must finish the cleanup: the proxy
        // is closed and no state is left tracked. The baseline has already lost the state on
        // the first attempt, so this second call never reaches the proxy to close it.
        adapter.failRemoval.set(false);
        provider.deprovision("fn");

        assertThat(proxy.closed)
                .as("after a successful retry the proxy must be closed")
                .isTrue();
        assertThat(trackedStates(provider))
                .as("after a successful retry no provider state remains")
                .isZero();
    }
}
