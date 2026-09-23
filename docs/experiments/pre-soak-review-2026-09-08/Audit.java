package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.dispatch.*;
import it.unimib.datai.nanofaas.controlplane.service.*;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Diagnostic reproductions, deliberately asserting the observed defective behavior. */
public class Audit {
    static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }
    static InvocationExecutionFactory.ExecutionLookup lookup(InvocationExecutionFactory factory, String key) {
        return factory.createOrReuseExecution("fn", spec("fn"), new InvocationRequest("payload", Map.of()), key, null, InvocationKind.SYNC);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static int privateSize(Object object, String field) throws Exception {
        var f = object.getClass().getDeclaredField(field);
        f.setAccessible(true);
        Object value = f.get(object);
        return value instanceof Map<?,?> map ? map.size() : ((Collection<?>) value).size();
    }
    static class Fixture {
        final ExecutionStore store = new ExecutionStore();
        final IdempotencyStore keys = new IdempotencyStore();
        final Metrics metrics = new Metrics(new SimpleMeterRegistry());
        final CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        final InvocationExecutionFactory factory = new InvocationExecutionFactory(store, keys, metrics);
        final ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
            new DispatcherRouter(new LocalDispatcher() {
                @Override public CompletableFuture<DispatchResult> dispatch(InvocationTask task) { return backend; }
            }, null), metrics);
        ReactiveInvocationCoordinator coordinator(OffloadGateway offload) {
            return new ReactiveInvocationCoordinator(null, metrics, null, offload, handler, new InvocationResponseMapper());
        }
    }
    static void waiterReplay() throws Exception {
        var f = new Fixture();
        var coordinator = f.coordinator(null);
        var first = lookup(f.factory, "key");
        var longWaiter = coordinator.invoke(first, spec("fn"), 10000).toFuture();
        var shortWaiter = coordinator.invoke(lookup(f.factory, "key"), spec("fn"), 1).block(Duration.ofSeconds(5));
        f.backend.complete(DispatchResult.warm(InvocationResult.success("real answer")));
        var result = longWaiter.get(5, TimeUnit.SECONDS).response();
        var replay = coordinator.invoke(lookup(f.factory, "key"), spec("fn"), 10000).block().response();
        require(result.status().equals("success") && replay.status().equals("timeout"), "waiter replay reproduction");
        System.out.printf("WAITER: short=%s long=%s/%s replay=%s/%s archived=%s%n", shortWaiter.response().status(), result.status(), result.output(), replay.status(), replay.output(), f.store.outcomeOf(first.executionRecord().executionId()).state());
    }
    static void offloadRetention() throws Exception {
        var f = new Fixture();
        Sinks.One<InvocationResult> remote = Sinks.one();
        OffloadGateway gateway = new OffloadGateway() {
            public boolean enabled() { return true; }
            public boolean shouldOffloadEagerly(FunctionSpec s) { return true; }
            public boolean shouldOffloadOnPressure(FunctionSpec s) { return false; }
            public String targetUrl(FunctionSpec s) { return "http://remote"; }
            public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context, int budget) { return remote.asMono(); }
        };
        var coordinator = f.coordinator(gateway);
        var first = lookup(f.factory, "offloaded");
        var owner = coordinator.invoke(first, spec("fn"), 10000).toFuture();
        coordinator.invoke(lookup(f.factory, "offloaded"), spec("fn"), 1).block(Duration.ofSeconds(5));
        remote.tryEmitValue(InvocationResult.success("remote success"));
        require(f.store.inFlightCount() == 1 && !first.executionRecord().completion().isDone(), "offload retention reproduction");
        System.out.printf("OFFLOAD: live=%d sharedFutureDone=%s ownerDone=%s archived=%s%n", f.store.inFlightCount(), first.executionRecord().completion().isDone(), owner.isDone(), f.store.outcomeOf(first.executionRecord().executionId()));
        owner.cancel(true);
    }
    static void weights() {
        int count = 30;
        int bytes = 1024 * 1024;
        var props = new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30), 100, 100, 11600);
        var store = new ExecutionStore(props, com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        int weight = 0;
        for (int i = 0; i < count; i++) {
            Object payload = new String(new char[bytes]).replace('\0', (char) ('a' + i % 26));
            for (int depth = 0; depth < 5; depth++) payload = List.of(payload);
            var task = new InvocationTask("w"+i, "fn", spec("fn"), new InvocationRequest(null, Map.of()), null, null, Instant.now(), 1, InvocationKind.ASYNC);
            var record = new ExecutionRecord(task.executionId(), task);
            store.put(record);
            record.markSuccess(payload);
            weight = OutcomeWeigher.weigh(record.toOutcome());
            store.settle(record);
        }
        require(store.size() == count, "nested payload retention reproduction");
        System.out.printf("WEIGHT: budget=%d estimatedPerOutcome=%d retained=%d uniquePayloadBytes=%d%n", props.maxOutcomeBytes(), weight, store.size(), (long) count*bytes);
        List<Object> wide = new ArrayList<>(Collections.nCopies(256, null));
        wide.add("x".repeat(bytes));
        var wideOutcome = new Outcome(ExecutionState.SUCCESS, 1, 2, wide, null, null, null, 0, -1, false, true);
        System.out.printf("WEIGHT-WIDE: actualPayloadBytes=%d estimate=%d%n", bytes, OutcomeWeigher.weigh(wideOutcome));
    }
    static void keyBudget() throws Exception {
        int threads = 16;
        var barrier = new CyclicBarrier(threads);
        var keys = new IdempotencyStore(new ExecutionStoreProperties(null,null,null,100,1,11600), com.github.benmanes.caffeine.cache.Ticker.systemTicker()) {
            @Override public int size() {
                int observed = super.size();
                try { barrier.await(5, TimeUnit.SECONDS); } catch(Exception e) { throw new RuntimeException(e); }
                return observed;
            }
        };
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<IdempotencyStore.AcquireResult>>();
            for(int i=0;i<threads;i++) { int n=i; futures.add(executor.submit(() -> keys.acquireOrGet("fn", "key"+n))); }
            int claimed=0;
            for(var future:futures) if(future.get(5, TimeUnit.SECONDS).state()==IdempotencyStore.AcquireResult.State.CLAIMED) claimed++;
            require(claimed==threads,"key budget reproduction");
            System.out.printf("KEY-BUDGET: configured=1 concurrentClaims=%d%n",claimed);
        }
    }
    static void archiveRace() throws Exception {
        var props = new ExecutionStoreProperties(null,null,null,100,100,100);
        var store = new ExecutionStore(props, com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        var keys = new IdempotencyStore(props, com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        var archived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // Pause at the actual boundary after outcome publication/live invalidation but before key finalization.
        store.onTerminal(record -> {
            store.size();
            archived.countDown();
            try { require(release.await(5, TimeUnit.SECONDS), "listener release"); } catch(InterruptedException e) { throw new RuntimeException(e); }
        });
        var metrics = new Metrics(new SimpleMeterRegistry());
        var factory = new InvocationExecutionFactory(store, keys, metrics);
        var first = lookup(factory, "race");
        first.publishAdmission(); // The first asynchronous dispatch is still pending.
        try(var executor=Executors.newSingleThreadExecutor()) {
            var settling = executor.submit(() -> { first.executionRecord().markSuccess("x".repeat(1000)); store.settle(first.executionRecord()); });
            require(archived.await(5, TimeUnit.SECONDS), "archive barrier");
            var replay = lookup(factory, "race");
            boolean newExecution = replay.isNew();
            release.countDown();
            settling.get(5, TimeUnit.SECONDS);
            require(newExecution, "archive race reproduction");
            System.out.printf("ARCHIVE-RACE: original=%s replay=%s replayIsNew=%s%n", first.executionRecord().executionId(), replay.executionRecord().executionId(), newExecution);
            replay.abandonAdmission();
        } finally { release.countDown(); }
    }
    static void churn() throws Exception {
        var meters = new SimpleMeterRegistry();
        var metrics = new Metrics(meters);
        var snapshot = new ReplicaStatusSnapshot(InstantSource.system(), Duration.ofSeconds(5), Runnable::run);
        for(int i=0;i<1000;i++) {
            String name="deleted-"+i;
            metrics.registerFunction(name);
            metrics.removeFunction(name);
            snapshot.read(new ManagedDeploymentTarget(name,"container-local"), target -> new ReplicaStatus(1,1));
            snapshot.invalidate(name);
        }
        require(privateSize(metrics,"removedFunctions")==1000 && privateSize(snapshot,"entries")==1000,"churn reproduction");
        System.out.printf("CHURN: removedMetricNames=%d invalidatedReplicaEntries=%d%n", privateSize(metrics,"removedFunctions"),privateSize(snapshot,"entries"));
    }
    static void coreAdmission() {
        var f = new Fixture();
        var coordinator = f.coordinator(null);
        var waiting = new ArrayList<CompletableFuture<SyncInvocation>>();
        for(int i=0;i<100;i++) waiting.add(coordinator.invoke(lookup(f.factory,null),spec("fn"),10000).toFuture());
        require(f.store.inFlightCount()==100,"direct admission reproduction");
        System.out.printf("CORE-ADMISSION: configuredConcurrency=1 liveDispatched=%d%n",f.store.inFlightCount());
        f.backend.complete(DispatchResult.warm(InvocationResult.success("done")));
    }
    static void deprovisionFailure() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var failRemoval = new java.util.concurrent.atomic.AtomicBoolean();
        var adapterType = it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerRuntimeAdapter.class;
        var adapter = (it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerRuntimeAdapter)
            java.lang.reflect.Proxy.newProxyInstance(adapterType.getClassLoader(), new Class<?>[]{adapterType}, (p,m,a) -> {
                if(m.getName().equals("isAvailable")) return true;
                if(m.getName().equals("listManagedContainers")) return List.of();
                if(m.getName().equals("removeContainer") && failRemoval.get()) throw new IllegalStateException("Docker unavailable");
                return null;
            });
        var proxy = new it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ManagedFunctionProxy() {
            public String endpointUrl() { return "http://127.0.0.1:9000/invoke"; }
            public void updateBackends(List<String> urls) { }
            public void close() { closed.set(true); }
        };
        var probe = new it.unimib.datai.nanofaas.modules.containerdeploymentprovider.EndpointProbe() {
            public boolean isReady(String url) { return true; }
            public void awaitReady(String url, Duration timeout, Duration poll) { }
        };
        var provider = new it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerLocalDeploymentProvider(adapter,
            new it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerLocalProperties(null,null,null,null,null),probe,()->9001,name->proxy);
        provider.provision(new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null, null));
        failRemoval.set(true);
        try { provider.deprovision("fn"); throw new AssertionError("expected removal failure"); } catch(IllegalStateException expected) { }
        require(!closed.get() && privateSize(provider,"states")==0,"deprovision resource leak reproduction");
        System.out.printf("DEPROVISION: removalFailed=true proxyClosed=%s trackedStates=%d%n",closed.get(),privateSize(provider,"states"));
    }
    public static void main(String[] args) throws Exception {
        waiterReplay(); offloadRetention(); weights(); keyBudget(); archiveRace(); churn();
        coreAdmission(); deprovisionFailure();
        System.out.println("All eight diagnostic reproductions observed.");
    }
}
