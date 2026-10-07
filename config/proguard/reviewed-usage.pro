# Reviewed keep rules for the report-only deadCodePublic task.
# Reviewed 2026-10-07 against 7087774: source, tests, reflection and contracts.
# Exact members only: new unused members in these classes must stay visible.
# Add a narrow rule with its reason/caller; do not copy complete class wildcards.
# These rules are analysis entry points, not a runtime or bytecode change.

# Framework callbacks: Spring @Import and named destroyMethod.
# Imported by ManagedDeploymentOrchestration via @Import; owns replica snapshot refresh pools.
-keep,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.config.ReplicaStatusSnapshotConfiguration {
    <init>();
}

# Callers/contract: ExecutorBackedInvocationEnqueuer.java:231, InvocationEnqueuerAutoConfiguration.java:45.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.ExecutorBackedInvocationEnqueuer {
    void shutdown();
}

# Test/experiment hooks: tests are deliberately excluded from the call graph.
# Callers/contract: RootCommandTest.java:132.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.config.ConfigStore {
    public void save(it.unimib.datai.nanofaas.cli.config.Config);
}

# Callers/contract: ReplicaSlotsTest.java:11.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.ReplicaSlots {
    public java.util.Optional tryAcquire(java.lang.String);
}

# Callers/contract: P13BoundedProxyTest.java:162, P19ProxyProfile.java:28.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy {
    it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy$Snapshot snapshot();
}

# Read by RoundRobinFunctionProxy.snapshot(), retained for P13BoundedProxyTest.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy$BufferBudget {
    private long used();
}

# P13BoundedProxyTest queries this snapshot to verify in-flight and buffered-memory ownership.
-keep,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy$Snapshot {
    <init>(int,long);
}

# Callers/contract: FunctionCapacityRegistryTest.java:351.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry {
    int entryCount();
}

# Callers/contract: FunctionCapacityRegistryTest.java:231, FunctionCapacityRegistryTest.java:351.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityState {
    void releaseSlot();
    public it.unimib.datai.nanofaas.controlplane.capacity.GenerationPhase phase();
}

# Callers/contract: GenerationLifecycleTest.java:25.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.GenerationLifecycle {
    public it.unimib.datai.nanofaas.controlplane.capacity.GenerationPhase phase();
}

# Callers/contract: InvocationCapacityTest.java:29, InvocationQuotaLifecycleIntegrationTest.java:242.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity {
    <init>(it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry,it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota,it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota,int);
    public long executionReservedForFunction(java.lang.String);
    public long inputReservedForFunction(java.lang.String);
    public long physicalInputCopyReservedForFunction(java.lang.String);
}

# Callers/contract: ResourceQuotaTest.java:99, ResourceQuotaRuntimeLimitsTest.java:30.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota {
    public long reservedForFunction(java.lang.String);
    public long reservedForGeneration(it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration);
}

# Callers/contract: ResourceQuotaTest.java:36, ResourceQuotaTest.java:37.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota$Reservation {
    public it.unimib.datai.nanofaas.controlplane.capacity.ResourceOwner owner();
    public long units();
}

# Callers/contract: InvocationQuotaHttpTest.java:128, P07dWaiterAdmissionTest.java:186, WaiterCapacityTest.java:40.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity {
    public long reservedGlobally();
    public long reservedForFunction(java.lang.String);
    public long reservedForGeneration(it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration);
}

# Callers/contract: DeploymentWakeUpCoordinatorTest.java:67, DeploymentWakeUpCoordinatorTest.java:80.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator {
    int ownedStateCount();
    int ownedLeaseCount();
}

# Callers/contract: ContainerLocalDeprovisionRecoveryTest.java:61.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException {
    public java.lang.String functionName();
}

# Callers/contract: ReplicaStatusSnapshotTest.java:208, ReplicaStatusSnapshotTest.java:207, ReplicaStatusSnapshotTest.java:215, ReplicaStatusSnapshotTest.java:78.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot {
    public int queueDepth(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot$RefreshPath);
    public int activeRefreshes(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot$RefreshPath);
    public long rejectedRefreshes(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot$RefreshPath);
    public long failedRefreshes(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot$RefreshPath);
    public long completedRefreshes(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot$RefreshPath);
}

# Callers/contract: ExecutionExpiryOwnershipTest.java:43, ExecutionCompletionHandlerAdministrativeExpiryTest.java:102, ExecutionCompletionHandlerAdministrativeExpiryTest.java:43, ExecutionRecordStateTransitionTest.java:54.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord {
    public void markSuccess(java.lang.Object);
    public void markTimeout();
    public boolean holdsDispatchLease();
    public java.time.Instant startedAt();
    public java.time.Instant finishedAt();
    public java.time.Instant admittedAt();
    public java.lang.Object output();
}

# Callers/contract: P07ConfiguredHttpCalibrationTest.java:85, P07ConfiguredHttpCalibrationTest.java:79, InvocationHotPathPerfTest.java:97, ExecutionStoreAdministrativeExpiryTest.java:68.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore {
    public int size();
    public int inFlightCount();
    public java.util.Optional get(java.lang.String);
    void cleanUp();
}

# Callers/contract: IdempotencyOutlivesExecutionTest.java:40, InvocationServiceDispatchTest.java:577, P07ConfiguredHttpCalibrationTest.java:85, IdempotencyKeyBudgetTest.java:201.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore {
    public java.util.Optional getExecutionId(java.lang.String,java.lang.String);
    public void put(java.lang.String,java.lang.String,java.lang.String);
    public int size();
    long occupied();
    long rejections();
}

# Callers/contract: OutcomeWeigherTest.java:105.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeigher {
    static it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeigher$FreezeResult freeze(java.lang.String,it.unimib.datai.nanofaas.controlplane.execution.Outcome);
}

# Callers/contract: FunctionApplicationPendingRecoveryTest.java:232, FunctionApplicationPendingRecoveryTest.java:233.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionApplicationState {
    int retainedFunctionCount();
    int retainedMarkerCount();
}

# Callers/contract: FunctionApplicationPendingRecoveryTest.java:233.
# Read by retainedMarkerCount(), retained for FunctionApplicationPendingRecoveryTest.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionApplicationState$Applications {
    int markerCount();
}

# Callers/contract: FunctionOperationLocksTest.java:24.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks {
    public void withLock(java.lang.String,java.lang.Runnable);
}

# Callers/contract: FunctionApplicationPendingRecoveryTest.java:328, FunctionRegistryPersistenceTest.java:40, FunctionRegistryPersistenceTest.java:55, FunctionApplicationPendingRecoveryTest.java:419.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry {
    public java.util.Collection list();
    public it.unimib.datai.nanofaas.common.model.FunctionSpec put(it.unimib.datai.nanofaas.common.model.FunctionSpec);
    public it.unimib.datai.nanofaas.common.model.FunctionSpec remove(java.lang.String);
    void replaceAllDurably(java.util.Collection);
}

# Callers/contract: FunctionServiceTest.java:135.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionService {
    public java.util.Collection list();
}

# Callers/contract: DeploymentWakeUpGateTest.java:142.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.DeploymentWakeUpGate {
    int ownedWakeUpCount();
}

# Callers/contract: EngineSyncQueueGatewaySettlementTest.java:103.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway {
    public boolean enqueue(it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask,java.time.Instant);
}

# Callers/contract: InvocationServiceDispatchTest.java:889.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.InvocationService {
    public reactor.core.publisher.Mono invokeSyncReactive(java.lang.String,it.unimib.datai.nanofaas.common.model.InvocationRequest,java.lang.String,java.lang.String,java.lang.Integer);
}

# Callers/contract: ExecutionCompletionHandlerTest.java:73, ExecutionCompletionHandlerTest.java:76, ExecutionCompletionHandlerTest.java:74, ExecutionCompletionHandlerRetryMetricsRegressionTest.java:81.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.Metrics {
    public io.micrometer.core.instrument.Timer latency(java.lang.String);
    public io.micrometer.core.instrument.Timer initDuration(java.lang.String);
    public io.micrometer.core.instrument.Timer queueWait(java.lang.String);
    public io.micrometer.core.instrument.Timer e2eLatency(java.lang.String);
}

# Callers/contract: ReactiveInvocationCoordinatorTest.java:40.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.ReactiveInvocationCoordinator {
    public reactor.core.publisher.Mono invoke(it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory$ExecutionLookup,it.unimib.datai.nanofaas.common.model.FunctionSpec,java.lang.Integer);
}

# SchedulerEngineQueueSnapshotTest checks pending/claimed/submitting/delayed and per-generation quotas.
-keep,includedescriptorclasses class it.unimib.datai.nanofaas.execution.EngineQueueSnapshot {
    public <init>(int,int,int,int,java.util.Map);
}

# Callers/contract: PendingWorkStoreTest.java:163.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.PendingWorkStore {
    public int claimedCount();
    public int submittingCount();
}

# Callers/contract: EngineWorkloadMetricsTest.java:52, RetryBackoffIntegrationTest.java:54.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.SchedulerEngine {
    public it.unimib.datai.nanofaas.execution.EngineQueueSnapshot snapshotQueues();
    public void tick();
}

# Callers/contract: SchedulerEngineSwitchTest.java:198.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.SchedulerSwitchException {
    public it.unimib.datai.nanofaas.execution.SchedulerSwitchException$Reason reason();
}

# Callers/contract: WaitEstimatorTest.java:52, WaitEstimatorRetentionTest.java:199, WaitEstimatorRetentionTest.java:169, WaitEstimatorRetentionTest.java:34.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.admission.WaitEstimator {
    <init>(java.time.Duration,int,java.util.Deque,java.util.Map);
    <init>(java.time.Duration,int,int,int,int,int,int);
    <init>(java.time.Duration,int,int,int,int,int,int,int);
    public it.unimib.datai.nanofaas.execution.admission.WaitEstimator$RetentionSnapshot retentionSnapshot();
}

# WaitEstimatorRetentionTest checks bounded history and sample retention.
-keep,includedescriptorclasses class it.unimib.datai.nanofaas.execution.admission.WaitEstimator$RetentionSnapshot {
    public <init>(int,int,int,int);
}

# Callers/contract: ColdStartTrackerTest.java:12.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.autoscaler.ColdStartTracker {
    public boolean isPotentialColdStart(java.lang.String);
}

# Callers/contract: DockerJavaContainerRuntimeAdapterIntegrationTest.java:29, DockerJavaContainerRuntimeAdapterIntegrationTest.java:66.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.containerdeploymentprovider.DockerJavaContainerRuntimeAdapter {
    <init>(com.github.dockerjava.api.DockerClient);
    <init>(com.github.dockerjava.api.DockerClient,java.lang.String);
    <init>(com.github.dockerjava.api.DockerClient,java.lang.String,java.lang.String);
}

# Callers/contract: CliContainerRuntimeAdapterTest.java:22.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ExecutionResult {
    static it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ExecutionResult success(java.lang.String);
}

# Callers/contract: OracleForecastStoreTest.java:25, ForecastControllerTest.java:45.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.forecasting.OracleForecastStore {
    public long revision();
}

# Callers/contract: DefaultOffloadGatewayTest.java:200.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway {
    int legacyOwnerCount();
}

# Callers/contract: DefaultOffloadGatewayTest.java:200.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway$LegacyMeterLifecycle {
    private int ownerCount();
}

# Callers/contract: ReplicaPlanActuatorTest.java:27.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator {
    public reactor.core.publisher.Mono prepare(it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochOutcome);
}

# Callers/contract: P2pLifecycleTest.java:41, P2pLifecycleTest.java:46.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pService {
    public java.lang.String address();
    it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerTable tableForTest();
}

# Callers/contract: NanofaasRuntimeOwnershipTest.java:117, NanofaasRuntimeOwnershipTest.java:62, NanofaasRuntimeOwnershipTest.java:220, NanofaasRuntimeOwnershipTest.java:63.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$Builder {
    it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$Builder callbackClientFactory(java.util.function.Supplier);
    it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$Builder serverExecutorFactory(java.util.function.Supplier);
    it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$Builder serverExecutor(java.util.concurrent.ExecutorService);
    it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$Builder shutdownHooks(it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime$ShutdownHooks);
}

# Callers/contract: CallbackClientBoundedSerializationTest.java:29, CallbackClientOwnershipTest.java:31, CallbackClientDeadlineTest.java:33.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient {
    <init>(com.fasterxml.jackson.databind.ObjectMapper,java.lang.String,int);
    <init>(java.net.http.HttpClient,com.fasterxml.jackson.databind.ObjectMapper,java.lang.String);
    <init>(java.net.http.HttpClient,com.fasterxml.jackson.databind.ObjectMapper,java.lang.String,java.time.Duration,int,int[]);
}

# Callers/contract: InvokeHandlerOwnershipTest.java:139, InvokeHandlerTcpBodyDeadlineTest.java:38, CorpusRuntimeDriver.java:51.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandler {
    <init>(it.unimib.datai.nanofaas.common.runtime.FunctionHandler,it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient,it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics,com.fasterxml.jackson.databind.ObjectMapper,java.lang.String,java.util.concurrent.ThreadPoolExecutor,long);
    <init>(it.unimib.datai.nanofaas.common.runtime.FunctionHandler,it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient,it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics,com.fasterxml.jackson.databind.ObjectMapper,java.lang.String,java.util.concurrent.ThreadPoolExecutor,long,it.unimib.datai.nanofaas.sdk.lite.handler.RuntimeLimits);
    <init>(it.unimib.datai.nanofaas.common.runtime.FunctionHandler,it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient,it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics,com.fasterxml.jackson.databind.ObjectMapper,java.lang.String,java.util.concurrent.ThreadPoolExecutor,long,it.unimib.datai.nanofaas.sdk.lite.handler.RuntimeLimits,boolean);
}

# Callers/contract: RuntimeLimitsTest.java:10, RuntimeLimitsTest.java:26, RuntimeLimitsTest.java:18, RuntimeLimitsTest.java:19.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.handler.RuntimeLimits {
    <init>(int,int,long,int,int,int);
    int activeHandlers();
    int pendingCallbacks();
    long pendingCallbackBytes();
}

# Callers/contract: RuntimePayloadLimitsTest.java:14.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.RuntimePayloadLimits {
    boolean outputTooLarge(java.lang.Object);
}

# Documented API/SPI contracts and their implementation methods.
# Callers/contract: CapacityView.java:6, FunctionCapacityState.java:93, FunctionCapacityRegistry.java:230, CapacityView.java:7.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.CapacityView {
    public abstract int inFlight();
    public abstract int configuredConcurrency();
    public abstract int effectiveConcurrency();
}

# Callers/contract: DispatchCapacity.java:13, FunctionCapacityRegistry.java:230, DispatchCapacity.java:7.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity {
    public abstract int configuredConcurrency(java.lang.String);
}

# Callers/contract: ReplicaObservation.java:34, ReplicaObservation.java:54, ReplicaObservation.java:68, ReplicaPlanActuator.java:113.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation {
    public abstract it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation$State state();
    public abstract java.time.Instant observedAt();
    public java.time.Duration age(java.time.Instant);
}

# Common dispatch contract implemented by LocalDispatcher and ExternalDispatcher.
-keep,includedescriptorclasses interface it.unimib.datai.nanofaas.controlplane.dispatch.Dispatcher {
    public abstract java.util.concurrent.CompletableFuture dispatch(it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask);
}

# Callers/contract: SchedulingIndex.java:67, SchedulerSwitchBenchmark.java:256, SchedulerEngineDeadlineGuardRegressionTest.java:141.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex {
    public abstract int size();
}

# Callers/contract: PerFunctionSchedulingStrategy.java:153, SchedulingIndex.java:67.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy$PerFunctionIndex {
    public int size();
}

# Callers/contract: P2pService.java:361, P2pService.java:355, P2pLifecycleTest.java:52.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pService {
    public it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging messaging();
}

# Callers/contract: PeerCluster.java:169, PeerMessaging.java:36.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerCluster {
    public reactor.core.publisher.Mono send(java.lang.String,java.lang.String,byte[]);
}

# Callers/contract: PeerMessaging.java:51, PeerMessagingTest.java:35, PeerMessaging.java:66, PeerMessagingTest.java:55.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging {
    public reactor.core.publisher.Mono send(java.lang.String,java.lang.String,byte[]);
    public reactor.core.publisher.Mono broadcast(java.lang.String,byte[]);
}

# Callers/contract: PeerMessaging.java:34, PeerMessaging.java:36, PeerMessaging.java:54, PeerMessaging.java:71.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging$1 {
    public reactor.core.publisher.Mono send(java.lang.String,java.lang.String,byte[]);
}

# Callers/contract: PeerMessaging.java:14, PeerMessaging.java:54, PeerMessaging.java:71.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging$Wire {
    public abstract reactor.core.publisher.Mono send(java.lang.String,java.lang.String,byte[]);
}

# Callers/contract: SharedQueueSchedulingStrategy.java:101, SchedulingIndex.java:67.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy$SharedQueueIndex {
    public int size();
}

# Private utility constructors prevent accidental instantiation; also the implicit ReplicaCostDp constructor.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.commands.controlplane.config.RuntimeConfigErrorMapper {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.commands.fn.FunctionApplier {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.image.BuildSpecLoader {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.image.DockerBuildx {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.io.JsonInput {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.io.YamlIO {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.common.logging.LogSanitizer {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.api.ApiErrorResponses {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeigher {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueueSupport {
    private <init>();
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.oneshot.solver.ReplicaCostDp {
    <init>();
}

# Compile-time constants with verified source uses: javac inlines their values.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.commands.RootCommand {
    private static final java.lang.String DEFAULT_ENDPOINT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.cli.http.ControlPlaneClient {
    private static final java.lang.String FUNCTIONS_PATH;
    private static final java.lang.String RUNTIME_CONFIG_PATH;
    private static final java.lang.String APPLICATION_JSON;
    private static final java.lang.String CONTENT_TYPE;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig {
    private static final int DEFAULT_TARGET_PER_POD;
    private static final int DEFAULT_MIN_TARGET_PER_POD;
    private static final int DEFAULT_MAX_TARGET_PER_POD;
    private static final long DEFAULT_UPSCALE_COOLDOWN_MS;
    private static final long DEFAULT_DOWNSCALE_COOLDOWN_MS;
    private static final double DEFAULT_HIGH_LOAD_THRESHOLD;
    private static final double DEFAULT_LOW_LOAD_THRESHOLD;
    private static final long DEFAULT_TARGET_LATENCY_MS;
    private static final double DEFAULT_WEIGHT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider {
    private static final java.lang.String PROXY_RESOURCE;
    private static final int DEFAULT_CONCURRENCY;
    private static final long DEFAULT_TIMEOUT_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.ProxySettings {
    static final int DEFAULT_MAX_REQUEST_BYTES;
    static final int DEFAULT_MAX_RESPONSE_BYTES;
    static final long DEFAULT_MAX_BUFFERED_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy {
    static final int DEFAULT_MAX_IN_FLIGHT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.config.CaffeineRuntimeHints {
    private static final java.lang.String GENERATED_CACHE_CLASSES;
    private static final java.lang.String GENERATED_NAME;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.config.DispatchConnectionPool {
    private static final long NOT_EMPTY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties {
    private static final long DEFAULT_MAX_OUTCOMES;
    private static final long DEFAULT_MAX_KEYS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator {
    private static final java.lang.String GENERATION;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot {
    private static final java.lang.String REPLICA_STATUS_FOR;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.Outcome {
    static final int NO_STATUS;
    static final long ABSENT;
    static final long NO_INIT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeigher {
    private static final int FIXED_OVERHEAD_BYTES;
    private static final int REFERENCE_BYTES;
    private static final int MAX_DEPTH;
    private static final int MAX_ELEMENTS;
    private static final int MAX_VISITED_VALUES;
    private static final int MAP_ENTRY_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator {
    private static final long REFERENCE_BYTES;
    private static final long OBJECT_HEADER_BYTES;
    private static final long ARRAY_HEADER_BYTES;
    private static final long BOXED_SCALAR_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionApplicationState {
    private static final int MAX_REPORTED_RESOURCES;
    private static final int MAX_REASON_LENGTH;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalog {
    private static final int SCHEMA;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.registry.FunctionSpecResolver {
    private static final java.lang.String QUEUE_DEPTH_METRIC;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.DeploymentWakeUpGate {
    private static final java.lang.String WAKE_UP_CLOSED;
    private static final java.lang.String WAKE_UP_REMOVED;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuerAutoConfiguration {
    private static final int RETRY_POOL_CORE_SIZE;
    private static final int RETRY_POOL_MAX_SIZE;
    private static final int RETRY_POOL_QUEUE_CAPACITY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.Metrics {
    private static final java.lang.String FUNCTION_TAG;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration {
    private static final java.lang.String PER_FUNCTION_STRATEGY;
    private static final int DEFAULT_MAX_PENDING;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.examples.figlet.FigletHandler {
    private static final java.lang.String DEFAULT_FONT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.examples.jsontransform.JsonTransformHandler {
    private static final java.lang.String ERROR_KEY;
    private static final java.lang.String OPERATION_COUNT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.examples.jsontransformlite.JsonTransformLite {
    private static final java.lang.String ERROR_KEY;
    private static final java.lang.String OPERATION_COUNT;
    private static final int BAD_REQUEST;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.examples.romannumeral.RomanNumeralHandler {
    private static final java.lang.String ERROR_KEY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.examples.romannumerallite.RomanNumeralLite {
    private static final java.lang.String ERROR_KEY;
    private static final int UNPROCESSABLE;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.SchedulerEngine {
    private static final java.lang.String FUNCTION_NAME_REQUIRED;
    static final long EMPTY_QUEUE_AWAIT_MS;
    static final long CAPACITY_BLOCKED_AWAIT_MS;
    private static final int MAX_EXPIRED_PER_PASS;
    static final int MAX_SWITCH_REBUILD_TICKETS;
    static final long SWITCH_BUDGET_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.admission.WaitEstimator {
    private static final int DEFAULT_MAX_GLOBAL_SAMPLES;
    private static final int DEFAULT_MAX_PER_FUNCTION_SAMPLES;
    private static final int DEFAULT_MAX_FUNCTION_STATES;
    private static final int DEFAULT_MAX_TOTAL_PER_FUNCTION_SAMPLES;
    private static final int DEFAULT_CLEANUP_BUDGET;
    private static final int DEFAULT_MAINTENANCE_SAMPLE_BUDGET;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy {
    static final int DEFAULT_MAX_BATCH_PER_FUNCTION;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.autoscaler.ColdStartTracker {
    private static final long EXPIRY_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.autoscaler.ScalingCooldownTracker {
    private static final long SCALE_UP_COOLDOWN_MS;
    private static final long SCALE_DOWN_COOLDOWN_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.autoscaler.ScalingDecisionCalculator {
    private static final double DEFAULT_TARGET;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.autoscaler.ScalingProgressTracker {
    static final long PROGRESS_WINDOW_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.buildmetadata.BuildMetadataProvider {
    private static final java.lang.String PROPERTIES_RESOURCE;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.concurrencycontrol.AdaptivePerPodConcurrencyController {
    private static final double BASELINE_DRIFT_PER_TICK;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.concurrencycontrol.LoadForecast {
    private static final double LEVEL_WEIGHT;
    private static final double TREND_WEIGHT;
    private static final double DAMPING;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.concurrencycontrol.SloDemandEstimator {
    private static final double MIN_GRADIENT;
    private static final double EXTREME_DECAY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.concurrencycontrol.SojournConcurrencyController {
    private static final double HIGH_WATER;
    private static final double DRAIN_HORIZON_SECONDS;
    private static final double MIN_DRAIN_HORIZON_SECONDS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.containerddeploymentprovider.ContainerdRuntimeAdapter {
    private static final long CPU_PERIOD_MICROS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerLocalDeploymentProvider {
    static final java.lang.String BACKEND_ID;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.forecasting.ForecastController {
    static final int MAX_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesDeploymentBuilder {
    private static final java.lang.String APP_LABEL;
    private static final java.lang.String FUNCTION_LABEL;
    private static final int SCALE_TO_ZERO_STABILIZATION_SECONDS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway {
    private static final long TIMEOUT_MARGIN_MS;
    private static final java.lang.String REMOTE_PREFIX;
    private static final java.lang.String PROXY_HEADER_PREFIX;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway$LegacyMeterLifecycle {
    private static final java.lang.String FUNCTION_TAG;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.oneshot.auction.OneShotAuctionEngine {
    private static final int MAX_MESSAGES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.AuctionCodec {
    private static final int MAX_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalReplicaSolver {
    private static final double EPS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.DefaultPeerTransport {
    static final java.lang.String ANNOUNCEMENT_TOPIC;
    private static final int MAX_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.LatencyMonitor {
    static final java.lang.String TOPIC;
    private static final int PAYLOAD;
    private static final double MAX_COORD;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector {
    static final double HYSTERESIS;
    static final double MIN_RANK_MARGIN_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformationCodec {
    static final int MAX_BYTES;
    static final int CATEGORY_BYTES;
    static final int MAX_ENTRIES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformationExchange {
    private static final long TTL_MILLIS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pAdminController {
    private static final java.lang.String ERROR_KEY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pAdminGate {
    private static final java.lang.String PREFIX;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pSettings {
    private static final java.lang.String MAX_NEIGHBORS;
    private static final java.lang.String MAX_LATENCY_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerCluster {
    private static final java.lang.String Q_MSG;
    private static final java.lang.String Q_REQ;
    private static final java.lang.String Q_RES;
    private static final java.lang.String H_TOPIC;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerClusterCodec {
    static final int MAX_HEADERS;
    static final int MAX_COLLECTION_ENTRIES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging {
    static final java.lang.String RESERVED_PREFIX;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerTable {
    private static final int RTT_WINDOW;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi {
    private static final double CC;
    private static final double CE;
    private static final double MAX_ERROR;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.runtimeconfig.AdminRuntimeConfigController {
    private static final java.lang.String ERROR;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.runtimeconfig.ControlPlaneRuntimeConfigExtension {
    private static final java.lang.String RATE_MAX_PER_SECOND;
    private static final java.lang.String EXECUTIONS_GLOBAL;
    private static final java.lang.String EXECUTIONS_PER_FUNCTION;
    private static final java.lang.String CANONICAL_INPUT_GLOBAL;
    private static final java.lang.String CANONICAL_INPUT_PER_FUNCTION;
    private static final java.lang.String PHYSICAL_INPUT_GLOBAL;
    private static final java.lang.String PHYSICAL_INPUT_PER_FUNCTION;
    private static final java.lang.String WAITERS_GLOBAL;
    private static final java.lang.String WAITERS_PER_FUNCTION;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigService {
    private static final java.lang.String UPDATES_METRIC;
    private static final java.lang.String STATUS_TAG;
    private static final java.lang.String NAMESPACE_TAG;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.runtimeconfig.SchedulerRuntimeConfigExtension {
    private static final java.lang.String STRATEGY_KEY;
    private static final java.lang.String AVAILABLE_KEY;
    private static final java.lang.String PERSISTENCE_KEY;
    private static final java.lang.String PERSISTENCE;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy {
    static final int SCAN_LIMIT;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration {
    private static final long ESTIMATOR_MAINTENANCE_PERIOD_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics {
    private static final java.lang.String GLOBAL_FUNCTION_TAG;
    private static final java.lang.String FUNCTION_TAG;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.FunctionContext {
    private static final java.lang.String EXECUTION_ID_KEY;
    private static final java.lang.String TRACE_ID_KEY;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient {
    private static final int DEFAULT_MAX_ATTEMPTS;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandler {
    private static final java.lang.String ERROR_KEY;
    private static final java.lang.String OUTPUT_TOO_LARGE_CODE;
    private static final java.lang.String OUTPUT_SERIALIZATION_ERROR_CODE;
    private static final java.lang.String MESSAGE_KEY;
    private static final java.lang.String RETRY_AFTER;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.handler.RuntimeLimits {
    static final int DEFAULT_HANDLERS;
    static final int DEFAULT_CALLBACKS;
    static final int DEFAULT_INPUT_BYTES;
    static final int DEFAULT_OUTPUT_BYTES;
    static final int DEFAULT_CALLBACK_BYTES;
    static final long DEFAULT_PENDING_CALLBACK_BYTES;
    static final int DEFAULT_BODY_READ_TIMEOUT_MS;
    static final int DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS;
    static final int DEFAULT_CALLBACK_MAX_ATTEMPTS;
    static final int DEFAULT_SHUTDOWN_TIMEOUT_MS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics {
    private static final java.lang.String FUNCTION_LABEL;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.CallbackClient {
    private static final int MAX_RETRIES;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.CallbackDispatcher {
    private static final int QUEUE_CAPACITY;
    private static final long DEFAULT_MAX_PENDING_BYTES;
    private static final int DEFAULT_MAX_CALLBACK_BYTES;
    private static final long SHUTDOWN_TIMEOUT_SECONDS;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.HttpClientConfig {
    private static final int CONNECT_TIMEOUT_MS;
    static final java.lang.String CALLBACK_HTTP_CLIENT_BEAN;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.InvokeController {
    private static final java.lang.String DEFAULT_HANDLER_ERROR_MESSAGE;
    private static final java.lang.String ERROR_KEY;
    private static final java.lang.String RETRY_AFTER;
    private static final java.lang.String RUNTIME_STOPPING_MESSAGE;
    private static final java.lang.String RUNTIME_STOPPING_CODE;
    private static final java.lang.String MESSAGE_KEY;
    private static final java.lang.String OUTPUT_SERIALIZATION_ERROR_CODE;
    private static final java.lang.String OUTPUT_TOO_LARGE_CODE;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.MetricsController {
    private static final java.lang.String PROM_004;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.sdk.runtime.RuntimeMetricsFilter {
    private static final java.lang.String INVOCATIONS_COUNTER;
    private static final java.lang.String SUCCESS_TAG;
}

-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder {
    private static final java.lang.String FUNCTION_TAG;
}

# Constant alias consumed by tests.
# Callers/contract: ExecutionCompletionHandlerAdministrativeExpiryTest.java:64, ExecutionCompletionHandlerAdministrativeExpiryTest.java:153.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler {
    static final java.lang.String EXECUTION_EXPIRED_CODE;
}

# Java serialization uses serialVersionUID implicitly.
-keepclassmembers,includedescriptorclasses class it.unimib.datai.nanofaas.execution.SchedulerSwitchException {
    private static final long serialVersionUID;
}

# Constant holder classes used from source, absent from compiled caller references.
-keep,includedescriptorclasses class it.unimib.datai.nanofaas.modules.k8s.dispatch.NanofaasDeploymentConstants {
    private <init>();
    static final java.lang.String DEPLOYMENT_NAME_PREFIX;
    static final java.lang.String SERVICE_NAME_PREFIX;
    static final java.lang.String ANNOTATION_SCRAPE;
    static final java.lang.String ANNOTATION_PATH;
    static final java.lang.String ANNOTATION_PORT;
}

-keep,includedescriptorclasses class it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricNames {
    private <init>();
    public static final java.lang.String QUEUE_DEPTH;
    public static final java.lang.String IN_FLIGHT;
    public static final java.lang.String EFFECTIVE_CONCURRENCY;
    public static final java.lang.String DISPATCHABLE_BACKLOG;
}
