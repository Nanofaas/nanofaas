package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class MetricsHandler implements HttpHandler {
    private final PrometheusRegistry registry;
    private final PrometheusTextFormatWriter writer = new PrometheusTextFormatWriter(true);

    public MetricsHandler(PrometheusRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, registry.scrape());
        byte[] body = baos.toByteArray();

        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
