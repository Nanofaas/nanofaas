package it.unimib.datai.nanofaas.sdk.runtime;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CallbackCorpusProjectionTest {
    @Test
    void actualCallbacksOmitOnlyAbsentEnvelopeFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            CallbackClient client = new CallbackClient(RestClient.create(),
                    new RuntimeSettings("exec", null, server.url("/").toString(), "handler"), mapper);
            var payloads = new CallbackPayload[]{
                    CallbackPayload.success(mapper.valueToTree(Map.of("result", "ok"))),
                    CallbackPayload.error("HANDLER_ERROR", "Handler failed"),
                    CallbackPayload.successWithEnvelope(mapper.valueToTree("ok"), 201,
                            Map.of("x-test", "yes"), "base64")};
            var expected = new String[]{
                    "{\"success\":true,\"output\":{\"result\":\"ok\"},\"error\":null}",
                    "{\"success\":false,\"output\":null,\"error\":{\"code\":\"HANDLER_ERROR\",\"message\":\"Handler failed\"}}",
                    "{\"success\":true,\"output\":\"ok\",\"error\":null,\"statusCode\":201,\"headers\":{\"x-test\":\"yes\"},\"encoding\":\"base64\"}"};
            for (int i = 0; i < payloads.length; i++) {
                server.enqueue(new MockResponse().setResponseCode(204));
                assertTrue(client.sendResult("exec", payloads[i], "trace", "1"));
                assertEquals(mapper.readTree(expected[i]), mapper.readTree(server.takeRequest().getBody().readUtf8()));
            }
        }
    }
}
