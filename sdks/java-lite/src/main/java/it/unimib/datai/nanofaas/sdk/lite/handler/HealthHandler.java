package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HealthHandler implements HttpHandler {
    private static final byte[] RESPONSE = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, RESPONSE.length);
        exchange.getResponseBody().write(RESPONSE);
        exchange.close();
    }
}
