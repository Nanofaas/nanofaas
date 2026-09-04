package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects an invocation before its body is read. {@code RateLimiter.allow()} used to
 * run inside {@code InvocationService}, after HTTP decode, JSON deserialization and
 * controller dispatch - every refusal paid for all of that first, exactly when the
 * platform has the least to spend. Scoped to the two invocation suffixes only:
 * registration, listing and deletion were never rate-limited and stay that way here.
 */
@Component
public class RateLimitWebFilter implements WebFilter {

    private final RateLimiter rateLimiter;

    public RateLimitWebFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isInvocationPath(exchange) || rateLimiter.allow()) {
            return chain.filter(exchange);
        }
        // Drain the body instead of leaving it unread: reactor-netty otherwise has no
        // signal that the request is fully consumed, and can close the connection
        // instead of reusing it in keep-alive - a cost that would dwarf what a cheap
        // refusal saves.
        return exchange.getRequest().getBody()
                .doOnNext(DataBufferUtils::release)
                .then(Mono.defer(() -> {
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                }));
    }

    private static boolean isInvocationPath(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/v1/functions/") && (path.endsWith(":invoke") || path.endsWith(":enqueue"));
    }
}
