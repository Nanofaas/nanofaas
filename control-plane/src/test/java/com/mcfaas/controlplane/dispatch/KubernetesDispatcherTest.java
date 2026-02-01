package com.mcfaas.controlplane.dispatch;

import com.mcfaas.common.model.ExecutionMode;
import com.mcfaas.common.model.FunctionSpec;
import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.controlplane.config.KubernetesProperties;
import com.mcfaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableKubernetesMockClient(crud = true)
class KubernetesDispatcherTest {
    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void issue011_dispatchCreatesJob() {
        KubernetesProperties properties = new KubernetesProperties("default", "http://control-plane/v1/internal/executions", 10);
        KubernetesDispatcher dispatcher = new KubernetesDispatcher(client, properties);

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "mcfaas/function-runtime:0.5.0",
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
