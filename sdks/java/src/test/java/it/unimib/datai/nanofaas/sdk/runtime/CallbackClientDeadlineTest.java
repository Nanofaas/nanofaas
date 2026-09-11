package it.unimib.datai.nanofaas.sdk.runtime;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackClientDeadlineTest {
    @Test
    void configuredAttemptCountAndReadDeadlineBoundCallbackRetention() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.start();
            HttpClientConfig config = new HttpClientConfig(500);
            HttpClient http = config.callbackHttpClient();
            RestClient rest = config.restClient(http);
            CallbackClient client = new CallbackClient(rest,
                    new RuntimeSettings("execution", null, server.url("/callbacks").toString(), "handler"),
                    new ObjectMapper(), 1_024, 2);
            assertFalse(client.sendResult("execution", CallbackPayload.error("ERR", "failed"), null));
            assertEquals(2, server.getRequestCount());
            http.close();
        }

        try (MockWebServer stalled = new MockWebServer()) {
            stalled.enqueue(new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS));
            stalled.start();
            HttpClientConfig config = new HttpClientConfig(40);
            HttpClient http = config.callbackHttpClient();
            CallbackClient client = new CallbackClient(config.restClient(http),
                    new RuntimeSettings("execution", null, stalled.url("/callbacks").toString(), "handler"),
                    new ObjectMapper(), 1_024, 1);
            long started = System.nanoTime();

            assertFalse(client.sendResult("execution", CallbackPayload.error("ERR", "failed"), null));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700);
            http.close();
        }
    }
}
