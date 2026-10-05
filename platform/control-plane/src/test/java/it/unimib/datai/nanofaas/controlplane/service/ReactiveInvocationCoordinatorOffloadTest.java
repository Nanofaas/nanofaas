package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactiveInvocationCoordinatorOffloadTest {

    private static final String TARGET = "http://cloud:8080";

    private final ExecutionStore executionStore = new ExecutionStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final Metrics metrics = new Metrics(new SimpleMeterRegistry());
    private final TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(
            executionStore, idempotencyStore, metrics,
            "fn-budget", "fn-declined", "fn-eager", "fn-est-wait",
            "fn-fail", "fn-hop", "fn-pressure", "fn-timeout");
    private final InvocationExecutionFactory factory = runtime.factory();
    private final ExecutionCompletionHandler completionHandler = mock(ExecutionCompletionHandler.class);
    private final OffloadGateway offloadGateway = mock(OffloadGateway.class);
    private final SyncQueueGateway syncQueueGateway = mock(SyncQueueGateway.class);

    private ReactiveInvocationCoordinator coordinator(SyncQueueGateway syncGateway) {
        return new ReactiveInvocationCoordinator(null, metrics, syncGateway, offloadGateway,
                completionHandler, new InvocationResponseMapper(), runtime.waiters());
    }

    /** Stubs that mirror the real handler's offload completion paths. */
    private void wireOffloadCompletion(InvocationExecutionFactory.ExecutionLookup lookup) {
        doAnswer(inv -> {
            InvocationResult result = inv.getArgument(1);
            lookup.executionRecord().completion().complete(result);
            return null;
        }).when(completionHandler).completeOffloadedExecution(anyString(), any(InvocationResult.class));
        doAnswer(inv -> {
            OffloadFailedException failure = inv.getArgument(1);
            lookup.executionRecord().completion().completeExceptionally(failure);
            return null;
        }).when(completionHandler).failOffloadedExecution(anyString(), any(OffloadFailedException.class));
    }

    @Test void plannedRemoteFailurePinsDestinationAndNeverFallsBack() {
        var spec=spec("fn-fail",null);var lookup=lookup(spec);wireOffloadCompletion(lookup);
        var route=new it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute(it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.Kind.REMOTE,"http://b:8080","b",1,"grant",Map.of(),null);
        when(offloadGateway.planRoute(any(),any())).thenReturn(route);
        when(offloadGateway.invokePlannedRemote(any(),eq(route),any(),anyInt())).thenReturn(Mono.error(new OffloadFailedException(route.targetUrl(),false,"sent then disconnected")));
        assertThatThrownBy(()->coordinator(null).invoke(lookup,spec,1000).block()).isInstanceOf(OffloadFailedException.class);
        assertThat(lookup.executionRecord().plannedRoute()).isEqualTo(route);
        verify(offloadGateway,org.mockito.Mockito.times(1)).invokePlannedRemote(any(),eq(route),any(),anyInt());
        verify(offloadGateway,never()).invokeRemote(any(),any(),any(),anyInt());verify(completionHandler,never()).dispatch(any());
        assertThat(lookup.executionRecord().executionNode()).isNull();
    }
    @Test void plannedRemoteTrustedNodeIsRetainedSeparatelyFromFunctionHeaders() {
        var spec=spec("fn-eager",null);var lookup=lookup(spec);wireOffloadCompletion(lookup);
        var route=new it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute(it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.Kind.REMOTE,TARGET,"b",1,"grant",Map.of(),null);
        when(offloadGateway.planRoute(any(),any())).thenReturn(route);
        var result=InvocationResult.successWithEnvelope("out",200,Map.of("X-NanoFaaS-Execution-Node","fake"),null);
        when(offloadGateway.invokePlannedRemote(any(),eq(route),any(),anyInt())).thenReturn(Mono.just(new it.unimib.datai.nanofaas.controlplane.offload.PlannedRemoteResult(result,"b")));
        var response=coordinator(null).invoke(lookup,spec,1000).block();assertThat(response.executionNode()).isEqualTo("b");
        assertThat(lookup.executionRecord().toOutcome().executionNode()).isEqualTo("b");
    }
    @Test void plannedInboundLocalNeverUsesPressureOffload() {
        var spec=spec("fn-hop",new OffloadPolicy(null,null,"always"));var lookup=lookup(spec);
        when(offloadGateway.planRoute(any(),any())).thenReturn(it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.local("b"));
        var queue=mock(SyncQueueGateway.class);when(queue.enabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH,1)).when(queue).enqueueOrThrow(any());
        when(offloadGateway.enabled()).thenReturn(true);when(offloadGateway.shouldOffloadOnPressure(spec)).thenReturn(true);
        assertThatThrownBy(()->coordinator(queue).invoke(lookup,spec,1000,new OffloadContext(true,null,null)).block()).isInstanceOf(SyncQueueRejectedException.class);
        verify(offloadGateway,never()).invokeRemote(any(),any(),any(),anyInt());verify(offloadGateway,never()).invokePlannedRemote(any(),any(),any(),anyInt());
    }
    @Test
    void eagerPolicyOffloadsAndReturnsRemoteResult() {
        FunctionSpec spec = spec("fn-eager", new OffloadPolicy(null, null, "always"));
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(InvocationResult.success("remote-out")));

        SyncInvocation invocation = coordinator(null).invoke(lookup, spec, 1000).block();

        assertThat(invocation).isNotNull();
        assertThat(invocation.response().status()).isEqualTo("success");
        assertThat(invocation.response().output()).isEqualTo("remote-out");
        assertThat(invocation.offloadedTarget()).isEqualTo(TARGET);
        verify(completionHandler, never()).dispatch(any(InvocationTask.class));
    }

    @Test
    void waiterTimeoutOverrideDoesNotChangeTheSharedRemoteBudget() {
        FunctionSpec spec = spec("fn-budget", new OffloadPolicy(null, null, "always"));
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(InvocationResult.success("ok")));

        coordinator(null).invoke(lookup, spec, 250).block();

        // The override belongs only to this waiter; shared remote work keeps the function budget.
        verify(offloadGateway).invokeRemote(any(), eq(OffloadTrigger.EAGER), any(), eq(spec.timeoutMs()));
    }

    @Test
    void pressureRejectionOffloadsInsteadOf429() {
        FunctionSpec spec = spec("fn-pressure", null);
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(inv -> {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 3);
        }).when(syncQueueGateway).enqueueOrThrow(any());
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(false);
        when(offloadGateway.shouldOffloadOnPressure(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(InvocationResult.success("remote-out")));

        SyncInvocation invocation = coordinator(syncQueueGateway).invoke(lookup, spec, 1000).block();

        assertThat(invocation).isNotNull();
        assertThat(invocation.response().status()).isEqualTo("success");
        assertThat(invocation.offloadedTarget()).isEqualTo(TARGET);
        verify(offloadGateway).invokeRemote(any(), eq(OffloadTrigger.DEPTH), any(), anyInt());
    }

    @Test
    void estimatedWaitPressureRejectionUsesEstimatedWaitTrigger() {
        FunctionSpec spec = spec("fn-est-wait", null);
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(inv -> {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.EST_WAIT, 3);
        }).when(syncQueueGateway).enqueueOrThrow(any());
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(false);
        when(offloadGateway.shouldOffloadOnPressure(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(InvocationResult.success("remote-out")));

        coordinator(syncQueueGateway).invoke(lookup, spec, 1000).block();

        verify(offloadGateway).invokeRemote(any(), eq(OffloadTrigger.EST_WAIT), any(), anyInt());
    }

    @Test
    void timeoutRejectionPropagatesWithoutTryingOffload() {
        FunctionSpec spec = spec("fn-timeout", null);
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(inv -> {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, 3);
        }).when(syncQueueGateway).enqueueOrThrow(any());
        when(offloadGateway.enabled()).thenReturn(true);

        ReactiveInvocationCoordinator coordinator = coordinator(syncQueueGateway);
        assertThatThrownBy(() -> invokeBlocking(coordinator, lookup, spec, 1000))
                .isInstanceOf(SyncQueueRejectedException.class);

        verify(offloadGateway, never()).shouldOffloadOnPressure(any());
        verify(offloadGateway, never()).invokeRemote(any(), any(), any(), anyInt());
    }

    @Test
    void pressureRejectionPropagatesWhenGatewayDeclines() {
        FunctionSpec spec = spec("fn-declined", null);
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(inv -> {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 3);
        }).when(syncQueueGateway).enqueueOrThrow(any());
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadOnPressure(any())).thenReturn(false);

        ReactiveInvocationCoordinator coordinator = coordinator(syncQueueGateway);
        assertThatThrownBy(() -> invokeBlocking(coordinator, lookup, spec, 1000))
                .isInstanceOf(SyncQueueRejectedException.class);
    }

    @Test
    void offloadedHopIsNeverReOffloaded() {
        FunctionSpec spec = spec("fn-hop", new OffloadPolicy(null, null, "always"));
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        when(offloadGateway.enabled()).thenReturn(true);
        doAnswer(inv -> {
            lookup.executionRecord().completion().complete(InvocationResult.success("local-out"));
            return null;
        }).when(completionHandler).dispatchDirect(any(InvocationTask.class));

        OffloadContext hop = new OffloadContext(true, null, null);
        SyncInvocation invocation = coordinator(null).invoke(lookup, spec, 1000, hop).block();

        assertThat(invocation).isNotNull();
        assertThat(invocation.response().output()).isEqualTo("local-out");
        assertThat(invocation.offloadedTarget()).isNull();
        verify(offloadGateway, never()).shouldOffloadEagerly(any());
        verify(offloadGateway, never()).invokeRemote(any(), any(), any(), anyInt());
    }

    @Test
    void offloadFailureSurfacesAsOffloadFailedException() {
        FunctionSpec spec = spec("fn-fail", new OffloadPolicy(null, null, "always"));
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.error(new OffloadFailedException(TARGET, false, "unreachable")));

        ReactiveInvocationCoordinator coordinator = coordinator(null);
        assertThatThrownBy(() -> invokeBlocking(coordinator, lookup, spec, 1000))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("unreachable")
                .matches(ex -> TARGET.equals(((OffloadFailedException) ex).targetUrl()));
    }

    @Test
    void offloadGatewayTimeoutKeepsGatewayTimeoutFlavor() {
        FunctionSpec spec = spec("fn-timeout", new OffloadPolicy(null, null, "always"));
        InvocationExecutionFactory.ExecutionLookup lookup = lookup(spec);
        wireOffloadCompletion(lookup);
        when(offloadGateway.enabled()).thenReturn(true);
        when(offloadGateway.shouldOffloadEagerly(spec)).thenReturn(true);
        when(offloadGateway.targetUrl(spec)).thenReturn(TARGET);
        when(offloadGateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenReturn(Mono.error(new OffloadFailedException(TARGET, true, "too slow")));

        ReactiveInvocationCoordinator coordinator = coordinator(null);
        assertThatThrownBy(() -> invokeBlocking(coordinator, lookup, spec, 1000))
                .isInstanceOf(OffloadFailedException.class)
                .matches(ex -> ((OffloadFailedException) ex).gatewayTimeout());
    }

    private InvocationExecutionFactory.ExecutionLookup lookup(FunctionSpec spec) {
        return factory.createOrReuseExecution(spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null,
                InvocationKind.SYNC
            );
    }

    private static FunctionSpec spec(String name, OffloadPolicy offload) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null, offload);
    }

    private static void invokeBlocking(ReactiveInvocationCoordinator coordinator,
                                       InvocationExecutionFactory.ExecutionLookup lookup,
                                       FunctionSpec spec, int budgetMs) {
        coordinator.invoke(lookup, spec, budgetMs).block();
    }
}
