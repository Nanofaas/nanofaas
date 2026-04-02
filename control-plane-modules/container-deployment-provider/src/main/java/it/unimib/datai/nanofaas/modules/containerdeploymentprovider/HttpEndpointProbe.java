package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpEndpointProbe implements EndpointProbe {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (isReady(baseUrl)) {
                return;
            }
            try {
                Thread.sleep(Math.max(1L, pollInterval.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for endpoint readiness", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for container endpoint to become ready: " + baseUrl);
    }

    @Override
    public boolean isReady(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }
}
