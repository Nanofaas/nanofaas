package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Rejects an invocation before its body is read. {@code RateLimiter.allow()} used to
 * run inside {@code InvocationService}, after HTTP decode, JSON deserialization and
 * controller dispatch - every refusal paid for all of that first, exactly when the
 * platform has the least to spend. Scoped to the two invocation suffixes only:
 * registration, listing and deletion were never rate-limited and stay that way here.
 *
 * <p>This is a globally component-scanned {@code WebFilter}, so it runs in every
 * {@code @WebFluxTest} slice too. A slice that doesn't otherwise need this filter will
 * still fail context loading with an opaque {@code NoSuchBeanDefinitionException} for
 * {@code RateLimiter} unless it adds {@code @Import(RateLimiter.class)} - this has
 * already happened once in this branch's history and cost a full debugging round.
 */
@Component
public class RateLimitWebFilter implements WebFilter {

    private static final PathPattern INVOKE_PATTERN =
            PathPatternParser.defaultInstance.parse("/v1/functions/{name}:invoke");
    private static final PathPattern ENQUEUE_PATTERN =
            PathPatternParser.defaultInstance.parse("/v1/functions/{name}:enqueue");

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
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                }));
    }

    private static boolean isInvocationPath(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return INVOKE_PATTERN.matches(path) || ENQUEUE_PATTERN.matches(path);
    }
}
