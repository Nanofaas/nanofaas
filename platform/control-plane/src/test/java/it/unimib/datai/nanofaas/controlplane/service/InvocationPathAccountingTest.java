package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * With sync-queue off and async-queue on - the configuration every comparison runs -
 * both doors call the same enqueue, so one FunctionQueueState holds both kinds of work
 * and every per-function meter reports a mixture. These are the three series that do
 * not, and the run that asks whether async displaced a waiting caller reads them.
 *
 * Asserted against a real registry rather than a mock, because what has to hold is not
 * that a method was called but that the series exists under the tags PromQL will ask
 * for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationPathAccountingTest {

    @Mock private FunctionService functionService;
    @Mock private InvocationEnqueuer enqueuer;
    @Mock private DispatcherRouter dispatcherRouter;
    @Mock private SyncQueueGateway syncQueueGateway;

    private MeterRegistry registry;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(registry);
        ExecutionStore executionStore = new ExecutionStore();
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, new IdempotencyStore(), rateLimiter,
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics));
    }

    @Test
    void theTwoDoorsAreAccountedApart() {
        FunctionSpec spec = spec("mixed-fn");
        when(functionService.get("mixed-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.enqueue(any())).thenReturn(true);

        invocationService.invokeSyncReactive(
                "mixed-fn", new InvocationRequest("payload", Map.of()), null, null, 50)
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .subscribe();
        invocationService.invokeAsync(
                "mixed-fn", new InvocationRequest("payload", Map.of()), null, null);

        assertThat(admitted("mixed-fn", "sync")).isEqualTo(1.0);
        assertThat(admitted("mixed-fn", "async")).isEqualTo(1.0);
        // The mixture the two are drawn from still reads as the total.
        assertThat(registry.get("function_enqueue_total").tag("function", "mixed-fn")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void aRefusalIsBookedAgainstTheDoorThatPaidForIt() {
        FunctionSpec spec = spec("full-fn");
        when(functionService.get("full-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.isQueueFull(anyString())).thenReturn(true);

        // Early refusal: no execution is built, so the only record it leaves is this one.
        assertThatThrownBy(() -> invocationService.invokeSyncReactive(
                "full-fn", new InvocationRequest("payload", Map.of()), null, null, 50).block())
                .isInstanceOf(QueueFullException.class);
        assertThatThrownBy(() -> invocationService.invokeAsync(
                "full-fn", new InvocationRequest("payload", Map.of()), null, null))
                .isInstanceOf(QueueFullException.class);

        assertThat(refused("full-fn", "sync")).isEqualTo(1.0);
        assertThat(refused("full-fn", "async")).isEqualTo(1.0);
    }

    @Test
    void aReplayIsOnlyCountedWhenAKeyFindsAnExecution() {
        FunctionSpec spec = spec("keyed-fn");
        when(functionService.get("keyed-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.enqueue(any())).thenReturn(true);

        invocationService.invokeAsync(
                "keyed-fn", new InvocationRequest("payload", Map.of()), "same-key", null);
        assertThatNoSeries("function_replayed_total", "keyed-fn", "async");

        // Second arrival with the same key: the execution is already on file.
        invocationService.invokeAsync(
                "keyed-fn", new InvocationRequest("payload", Map.of()), "same-key", null);
        assertThat(replayed("keyed-fn", "async")).isEqualTo(1.0);
    }

    private double admitted(String function, String path) {
        return counter("function_admitted_total", function, path);
    }

    private double refused(String function, String path) {
        return counter("function_refused_total", function, path);
    }

    private double replayed(String function, String path) {
        return counter("function_replayed_total", function, path);
    }

    private double counter(String name, String function, String path) {
        return registry.get(name).tag("function", function).tag("path", path).counter().count();
    }

    private void assertThatNoSeries(String name, String function, String path) {
        try {
            assertThat(counter(name, function, path)).isZero();
        } catch (MeterNotFoundException expected) {
            // never registered is as good as zero
        }
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "image", null, Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
}
