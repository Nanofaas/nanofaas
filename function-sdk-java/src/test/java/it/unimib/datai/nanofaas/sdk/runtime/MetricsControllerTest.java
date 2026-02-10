package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricsControllerTest {

    @Test
    void metrics_registryAvailable_returnsOkWithScrape() {
        PrometheusMeterRegistry registry = mock(PrometheusMeterRegistry.class);
        when(registry.scrape()).thenReturn("# HELP jvm_memory\n");

        @SuppressWarnings("unchecked")
        ObjectProvider<PrometheusMeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        MetricsController controller = new MetricsController(provider);
        ResponseEntity<String> response = controller.metrics();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("# HELP"));
    }

    @Test
    void metrics_registryNull_returns503() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PrometheusMeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MetricsController controller = new MetricsController(provider);
        ResponseEntity<String> response = controller.metrics();

        assertEquals(503, response.getStatusCode().value());
        assertTrue(response.getBody().contains("not configured"));
    }
}
