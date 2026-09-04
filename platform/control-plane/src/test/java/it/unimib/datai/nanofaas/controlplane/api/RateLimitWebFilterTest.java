package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
}
