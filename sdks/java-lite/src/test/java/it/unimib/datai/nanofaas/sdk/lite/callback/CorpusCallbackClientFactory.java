package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;

public final class CorpusCallbackClientFactory {
    private CorpusCallbackClientFactory() { }
    public static CallbackClient create(HttpClient http, ObjectMapper mapper, String url,
                                        long timeoutMs, int attempts) {
        return new CallbackClient(http, mapper, url, Duration.ofMillis(timeoutMs), attempts, new int[]{0, 0});
    }
}
