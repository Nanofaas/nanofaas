package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void configurationDoesNotCloseAnUnrelatedHttpClient() {
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

    @Test
    void unrelatedHttpClientsDoNotMakeCallbackClientResolutionAmbiguous() {
        HttpClient first = HttpClient.newHttpClient();
        HttpClient second = HttpClient.newHttpClient();
        try {
            var context = new AnnotationConfigApplicationContext();
            context.registerBean("firstApplicationHttpClient", HttpClient.class, () -> first,
                    definition -> definition.setDestroyMethodName(""));
            context.registerBean("secondApplicationHttpClient", HttpClient.class, () -> second,
                    definition -> definition.setDestroyMethodName(""));
            context.register(HttpClientConfig.class);
            context.refresh();

            assertNotNull(context.getBean(org.springframework.web.client.RestClient.class));
            context.close();
            assertFalse(first.isTerminated());
            assertFalse(second.isTerminated());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void namedCallbackClientOverrideWinsAlongsideUnrelatedClient() {
        HttpClient callback = HttpClient.newHttpClient();
        HttpClient unrelated = HttpClient.newHttpClient();
        try {
            var context = new AnnotationConfigApplicationContext();
            context.registerBean(HttpClientConfig.CALLBACK_HTTP_CLIENT_BEAN, HttpClient.class,
                    () -> callback, definition -> definition.setDestroyMethodName(""));
            context.registerBean("unrelatedHttpClient", HttpClient.class, () -> unrelated,
                    definition -> definition.setDestroyMethodName(""));
            context.register(HttpClientConfig.class);
            context.refresh();

            assertSame(callback, context.getBean(HttpClientConfig.CALLBACK_HTTP_CLIENT_BEAN));
            assertNotNull(context.getBean(org.springframework.web.client.RestClient.class));
            context.close();
            assertFalse(callback.isTerminated());
            assertFalse(unrelated.isTerminated());
        } finally {
            callback.close();
            unrelated.close();
        }
    }
}
