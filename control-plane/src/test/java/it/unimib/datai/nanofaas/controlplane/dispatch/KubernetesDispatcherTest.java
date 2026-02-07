package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableKubernetesMockClient(crud = true)
class KubernetesDispatcherTest {
    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void issue011_dispatchCreatesJob() {
        KubernetesProperties properties = new KubernetesProperties("default", "http://control-plane/v1/internal/executions", 10, null);
        KubernetesDispatcher dispatcher = new KubernetesDispatcher(client, properties, Executors.newSingleThreadExecutor());

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "nanofaas/function-runtime:0.5.0",
                List.of(),
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.REMOTE,
                null,
                null
        );
        InvocationTask task = new InvocationTask(
                "exec-1",
                "echo",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );

        dispatcher.dispatch(task).join();

        int jobCount = client.batch().v1().jobs().inNamespace("default").list().getItems().size();
        assertEquals(1, jobCount);
    }
}
