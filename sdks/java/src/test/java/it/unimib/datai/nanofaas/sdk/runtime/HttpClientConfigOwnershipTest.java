package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HttpClientConfigOwnershipTest {

    @Test
    void springClosesTheHttpClientCreatedByItsConfiguration() {
        HttpClient client;
        try (var context = new AnnotationConfigApplicationContext(HttpClientConfig.class)) {
            client = context.getBean(HttpClient.class);
            assertFalse(client.isTerminated());
        }

        await().atMost(2, TimeUnit.SECONDS).until(client::isTerminated);
    }

    @Test
    void configurationDoesNotCloseAnInjectedHttpClient() {
        HttpClient injected = HttpClient.newHttpClient();
        try {
            var context = new AnnotationConfigApplicationContext();
            context.registerBean("externalHttpClient", HttpClient.class, () -> injected,
                    definition -> definition.setDestroyMethodName(""));
            context.register(HttpClientConfig.class);
            context.refresh();

            context.close();

            assertFalse(injected.isTerminated(), "the external bean retains ownership");
        } finally {
            injected.close();
        }
    }
}
