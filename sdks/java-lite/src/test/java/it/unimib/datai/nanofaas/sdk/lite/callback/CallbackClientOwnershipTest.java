package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CallbackClientOwnershipTest {

    @Test
    void closeTerminatesTheHttpClientCreatedByTheCallbackClient() throws Exception {
        CallbackClient callbackClient = new CallbackClient(new ObjectMapper(), "http://127.0.0.1:1");
        HttpClient httpClient = httpClientOf(callbackClient);

        callbackClient.close();

        await().atMost(2, TimeUnit.SECONDS).until(httpClient::isTerminated);
    }

    @Test
    void closeDoesNotTerminateAnInjectedHttpClient() {
        try (HttpClient injected = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build()) {
            CallbackClient callbackClient = new CallbackClient(
                    injected, new ObjectMapper(), "http://127.0.0.1:1");

            callbackClient.close();

            assertFalse(injected.isTerminated(), "the injector retains ownership");
        }
    }

    private static HttpClient httpClientOf(CallbackClient callbackClient) throws Exception {
        Field field = CallbackClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(callbackClient);
    }
}
