package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds the outbound HTTP client used for callback delivery.
 *
 * <p>Callback posting is part of the invoke lifecycle, so connection setup and read timeouts
 * directly affect handler latency. The runtime keeps this client separate so callback behavior is
 * explicit and bounded instead of inheriting arbitrary defaults from the host application.</p>
 */
@Configuration
@ImportRuntimeHints(NanofaasRuntimeHints.class)
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    static final String CALLBACK_HTTP_CLIENT_BEAN = "nanofaasCallbackHttpClient";

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean(name = CALLBACK_HTTP_CLIENT_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean(name = CALLBACK_HTTP_CLIENT_BEAN)
    public HttpClient callbackHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @Bean
    public RestClient restClient(@Qualifier(CALLBACK_HTTP_CLIENT_BEAN) HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
