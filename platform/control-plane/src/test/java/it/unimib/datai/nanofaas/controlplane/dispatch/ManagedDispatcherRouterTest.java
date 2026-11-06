package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedDispatcherRouterTest {
    @Test void supportedButEmptyNeverFallsBackToAnExternalEndpoint() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var coordinator = mock(ManagedDeploymentCoordinator.class);
            when(coordinator.acquireInvocationTarget(any())).thenReturn(ManagedTargetResolution.UNAVAILABLE);
            var router = new DispatcherRouter(mock(LocalDispatcher.class), new ExternalDispatcher(WebClient.create()), coordinator);
            var result = router.dispatchExternal(task(server.url("/invoke").toString(), ExecutionMode.DEPLOYMENT)).get(2, TimeUnit.SECONDS);
            assertThat(result.result().success()).isFalse(); assertThat(result.result().statusCode()).isEqualTo(503);
            assertThat(server.getRequestCount()).isZero();
        }
    }
    @Test void unsupportedServiceAndExternalEndpointsKeepTheirExistingTransport() throws Exception {
        for (var mode : new ExecutionMode[]{ExecutionMode.DEPLOYMENT, ExecutionMode.EXTERNAL}) {
            try (var server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody("\"ok\"").addHeader("Content-Type", "application/json")); server.start();
                var coordinator = mock(ManagedDeploymentCoordinator.class);
                when(coordinator.acquireInvocationTarget(any())).thenReturn(ManagedTargetResolution.UNSUPPORTED);
                var router = new DispatcherRouter(mock(LocalDispatcher.class), new ExternalDispatcher(WebClient.create()), coordinator);
                assertThat(router.dispatchExternal(task(server.url("/invoke").toString(), mode)).get(2, TimeUnit.SECONDS).result().output()).isEqualTo("ok");
                assertThat(server.getRequestCount()).isEqualTo(1);
            }
        }
    }
    private static InvocationTask task(String endpoint, ExecutionMode mode) {
        var spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 2, 10, 3, endpoint, mode, null, null, null);
        return new InvocationTask("execution", "fn", spec, new InvocationRequest("input", null), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }
}
