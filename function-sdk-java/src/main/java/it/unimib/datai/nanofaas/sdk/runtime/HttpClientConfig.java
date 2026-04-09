package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HTTP client for callback delivery.
 *
 * <p>The callback path is part of the function request lifecycle, so this client is configured in
 * the SDK rather than left to user code. The runtime depends on outbound HTTP working with bounded
 * connect/read timeouts; otherwise callback delivery can hold the invocation open longer than
 * expected.</p>
 */
@Configuration
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    @Bean
    public RestClient restClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
