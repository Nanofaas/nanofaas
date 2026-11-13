package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RawJson;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RawPayloadDispatchTest {
    private static final String DOCUMENT = "{\"a\": [1, 2.50, {\"b\": null}],\n \"città\": \"x\"}";

    private static DispatchResult dispatch(MockResponse response) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(response);
            server.start();
            var spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 2, 10, 3,
                    server.url("/invoke").toString(), ExecutionMode.EXTERNAL, null, null, null);
            var task = new InvocationTask("execution", "fn", spec, new InvocationRequest("in", null), null, null,
                    Instant.now(), 1, InvocationKind.SYNC);
            return new ExternalDispatcher(WebClient.create()).dispatch(task).get(2, TimeUnit.SECONDS);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    @Test
    void aStructuredResultIsKeptAsTheBytesTheFunctionSent() throws Exception {
        for (MockResponse response : new MockResponse[]{
                json(DOCUMENT),
                json(DOCUMENT).addHeader("X-NanoFaaS-Function-Status", "true")}) {
            var result = dispatch(response).result();

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isInstanceOf(RawJson.class).hasToString(DOCUMENT);
        }
    }

    @Test
    void aStructuredResultThatIsNotValidJsonIsStillAPlatformError() throws Exception {
        for (String broken : new String[]{"{\"a\": [1, 2", "{\"a\": 1} trailing", "[1,,2]", "{\"a\": 1}{\"b\": 2}"}) {
            for (MockResponse response : new MockResponse[]{
                    json(broken), json(broken).addHeader("X-NanoFaaS-Function-Status", "true")}) {
                var result = dispatch(response).result();

                assertThat(result.success()).as(broken).isFalse();
                assertThat(result.error().code()).isEqualTo("EXTERNAL_ERROR");
            }
        }
    }

    @Test
    void scalarResultsAreStillPlainValues() throws Exception {
        assertThat(dispatch(json("\"ok\"")).result().output()).isEqualTo("ok");
        assertThat(dispatch(json("42")).result().output()).isEqualTo(42);
        assertThat(dispatch(json(" true ").addHeader("X-NanoFaaS-Function-Status", "true")).result().output()).isEqualTo(true);
        assertThat(dispatch(json("null")).result().output()).isNull();
    }

    @Test
    void theRuntimeReceivesThePayloadTextInsideTheUsualEnvelope() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("\"ok\"").addHeader("Content-Type", "application/json"));
            server.start();
            String payload = "{\"records\":[{\"id\":1,\"score\":2.50}],\"note\":\"città\"}";
            var request = new InvocationRequest(
                    RawJson.of(payload.getBytes(StandardCharsets.UTF_8), 12, 3, 5), Map.of("k", "v"), Map.of("h", "1"));
            var spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 2, 10, 3,
                    server.url("/invoke").toString(), ExecutionMode.EXTERNAL, null, null, null);
            var task = new InvocationTask("execution", "fn", spec, request, null, null, Instant.now(), 1, InvocationKind.SYNC);

            var result = new ExternalDispatcher(WebClient.create()).dispatch(task).get(2, TimeUnit.SECONDS);

            assertThat(result.result().success()).isTrue();
            assertThat(server.takeRequest().getBody().readUtf8())
                    .isEqualTo("{\"input\":" + payload + ",\"metadata\":{\"k\":\"v\"},\"headers\":{\"h\":\"1\"}}");
        }
    }
}
