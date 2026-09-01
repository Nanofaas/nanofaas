package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionApplierTest {

    private MockWebServer server;
    private ControlPlaneClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ControlPlaneClient(server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static FunctionSpec desired() {
        return new FunctionSpec("echo", "registry.example/echo:2",
                List.of(), Map.of(), null, null, null, null, null, null,
                ExecutionMode.DEPLOYMENT, null, null, null, null);
    }

    private static MockResponse existing() {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1",
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT",
                         "deploymentBackend":"k8s","runtimeMode":"HTTP"}
                        """);
    }

    private static MockResponse replicas() {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"desiredReplicas\":4,\"readyReplicas\":3}");
    }

    private static MockResponse replicasRestored() {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"function\":\"echo\",\"replicas\":4}");
    }

    @Test
    void replaceRollsBackWhenRegisterFailsAfterDelete() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(replicas());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));
        server.enqueue(replicasRestored());

        FunctionSpec desired = desired();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired, true));

        assertThat(ex).hasMessageContaining("restored");

        assertThat(server.getRequestCount()).isEqualTo(7);
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
        assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
        RecordedRequest replicaSnapshot = server.takeRequest();
        assertThat(replicaSnapshot.getMethod()).isEqualTo("GET");
        assertThat(replicaSnapshot.getPath()).isEqualTo("/v1/functions/echo/replicas");
        assertThat(server.takeRequest().getMethod()).isEqualTo("DELETE");
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
        RecordedRequest rollback = server.takeRequest();
        assertThat(rollback.getMethod()).isEqualTo("POST");
        assertThat(rollback.getBody().readUtf8()).contains("\"image\":\"registry.example/echo:1\"");
        RecordedRequest replicaRestore = server.takeRequest();
        assertThat(replicaRestore.getMethod()).isEqualTo("PUT");
        assertThat(replicaRestore.getPath()).isEqualTo("/v1/functions/echo/replicas");
        assertThat(replicaRestore.getBody().readUtf8()).contains("\"replicas\":4");
    }

    @Test
    void replaceMapsImageNotFoundAndStillRollsBack() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(replicas());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"IMAGE_NOT_FOUND\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));
        server.enqueue(replicasRestored());

        FunctionSpec desired = desired();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired, true));

        assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
        assertThat(ex.getCause()).hasMessageContaining("Image not found in registry");

        assertThat(server.getRequestCount()).isEqualTo(7);
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST"); // rollback
        assertThat(server.takeRequest().getMethod()).isEqualTo("PUT");
    }

    @Test
    void replaceReportsUnrestorableWhenRollbackFailsToo() {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(replicas());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        FunctionSpec desired = desired();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired, true));

        assertThat(ex).hasMessageContaining("could not be restored");
        assertThat(ex.getCause()).isNotNull();
        assertThat(ex.getCause().getSuppressed()).isNotEmpty();
    }

    @Test
    void replaceReportsUnrestorableWhenReplicaRollbackFails() {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(replicas());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        FunctionSpec desired = desired();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired, true));

        assertThat(ex).hasMessageContaining("could not be restored");
        assertThat(ex.getCause()).isNotNull();
        assertThat(ex.getCause().getSuppressed()).hasSize(1);
    }
}
