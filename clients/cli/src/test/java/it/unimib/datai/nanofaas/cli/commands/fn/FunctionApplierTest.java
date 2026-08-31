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
                         "runtimeMode":"HTTP"}
                        """);
    }

    @Test
    void replaceRollsBackWhenRegisterFailsAfterDelete() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired(), true));

        assertThat(ex).hasMessageContaining("restored");

        assertThat(server.getRequestCount()).isEqualTo(5);
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
        assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
        assertThat(server.takeRequest().getMethod()).isEqualTo("DELETE");
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
        RecordedRequest rollback = server.takeRequest();
        assertThat(rollback.getMethod()).isEqualTo("POST");
        assertThat(rollback.getBody().readUtf8()).contains("\"image\":\"registry.example/echo:1\"");
    }

    @Test
    void replaceMapsImageNotFoundAndStillRollsBack() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"IMAGE_NOT_FOUND\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired(), true));

        assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
        assertThat(ex.getCause()).hasMessageContaining("Image not found in registry");

        assertThat(server.getRequestCount()).isEqualTo(5);
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        server.takeRequest();
        assertThat(server.takeRequest().getMethod()).isEqualTo("POST"); // rollback
    }

    @Test
    void replaceReportsUnrestorableWhenRollbackFailsToo() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(existing());
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FunctionApplier.apply(client, desired(), true));

        assertThat(ex).hasMessageContaining("could not be restored");
        assertThat(ex.getCause()).isNotNull();
        assertThat(ex.getCause().getSuppressed()).isNotEmpty();
    }
}
