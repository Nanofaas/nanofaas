package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitWebFilterTest {

    @Test
    void refusesAnInvokeRequestWithoutCallingTheChain() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body("{}"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void refusesAnEnqueueRequestTheSameWay() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:enqueue").body("{}"));
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void letsTheRequestThroughWhenUnderTheLimit() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body("{}"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void ignoresPathsOutsideTheTwoInvocationSuffixesEvenWhenTheLimitIsExhausted() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void ignoresNonPostRequestsToInvocationPathsEvenWhenTheLimitIsExhausted() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/functions/echo:invoke").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void refusesAPercentEncodedInvokePathInsteadOfLettingItBypassTheFilter() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        // A percent-encoded colon: the raw path does not end with ":invoke" but the
        // decoded path within the application does. A filter that matches on the raw
        // string misses this and lets the request through despite the exhausted limit.
        // MockServerHttpRequest.post(String) would re-encode the literal "%3A" into
        // "%253A", so the already-encoded URI is passed directly instead.
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, URI.create("/v1/functions/echo%3Ainvoke"))
                        .body("{}"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void setsRetryAfterHeaderOnARefusedRequest() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body("{}"));
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
