package it.unimib.datai.nanofaas.sdk.runtime;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallbackClientSerializedTest {
    @Test
    void sendsTheAlreadyBoundedBytesWithoutObjectGraphReserialization() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(204));
            server.start();
            RuntimeSettings settings = mock(RuntimeSettings.class);
            when(settings.callbackUrl()).thenReturn(server.url("/v1/executions").toString());
            CallbackClient client = new CallbackClient(RestClient.builder().build(), settings,
                    JsonMapper.builder().build());
            byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);

            assertTrue(client.sendSerializedResult("exec", body, "trace", "7"));

            var request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertArrayEquals(body, request.getBody().readByteArray());
        }
    }
}
