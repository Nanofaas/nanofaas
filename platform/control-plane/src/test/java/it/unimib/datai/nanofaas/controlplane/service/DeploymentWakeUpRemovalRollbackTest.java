package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentWakeUpRemovalRollbackTest {

    @Test
    void failedRemovalBeforeCapacityListenerKeepsOriginalGenerationServiceableAndBounded() {
        FunctionRegistry registry = new FunctionRegistry();
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        FunctionGeneration originalGeneration = generations.activeGeneration("echo");
        FunctionSpec spec = functionSpec();
        registry.put(new RegisteredFunction(spec, new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)));

        ManagedDeploymentCoordinator deploymentCoordinator = mock(ManagedDeploymentCoordinator.class);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(deploymentCoordinator.generationOf(any(RegisteredFunction.class)))
                .thenAnswer(ignored -> generations.activeGeneration("echo"));
        when(deploymentCoordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(deploymentCoordinator.getFreshReplicaStatus(target))
                .thenReturn(new ReplicaStatus(1, 0), new ReplicaStatus(1, 1));

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        DeploymentWakeUpCoordinator wakeUpCoordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
        DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                registry,
                deploymentCoordinator,
                generations,
                new DeploymentWakeUpProperties(Duration.ofDays(2), Duration.ofDays(1)),
                Runnable::run,
                scheduler,
                wakeUpCoordinator,
                InstantSource.system(),
                System::nanoTime);
        FunctionRegistrationListener failingListener = new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec ignored) {
                // no-op: this test double ignores the call
            }

            @Override
            public void onRemove(String ignored) {
                throw new IllegalStateException("listener failure");
            }
        };
        FunctionRegistrationListener capacityListener = new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec registered) {
                generations.register(registered.name(), registered.concurrency());
            }

            @Override
            public void onRemove(String functionName) {
                generations.remove(functionName);
            }
        };
        FunctionService service = new FunctionService(
                registry,
                new FunctionDefaults(30_000, 4, 100, 3),
                ImageValidator.noOp(),
                List.of(gate, failingListener, capacityListener),
                new DeploymentProviderResolver(List.of(), new DeploymentProperties(null)),
                new FunctionOperationLocks(),
                deploymentCoordinator);

        try {
            assertThatThrownBy(() -> service.remove("echo"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("listener failure");
            assertThat(generations.activeGeneration("echo")).isEqualTo(originalGeneration);
            assertThat(service.get("echo")).contains(spec);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();

            var readiness = gate.ensureReady(task(spec));
            assertThat(gate.ownedWakeUpCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).hasSize(3);

            Runnable poll = (Runnable) scheduler.getQueue().peek();
            assertThat(scheduler.remove(poll)).isTrue();
            poll.run();

            assertThat(readiness).isCompletedWithValue(null);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
            assertThat(wakeUpCoordinator.scaleDownIfUnprotected(
                    originalGeneration, target, () -> true)).isTrue();

            gate.onRemove("echo");
            generations.remove("echo");
            assertThat(wakeUpCoordinator.scaleDownIfUnprotected(
                    originalGeneration, target, () -> true)).isFalse();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            gate.close();
            wakeUpCoordinator.close();
            scheduler.shutdownNow();
        }
    }

    private static InvocationTask task(FunctionSpec spec) {
        return new InvocationTask(
                "execution-echo",
                "echo",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1,
                InvocationKind.SYNC);
    }

    private static FunctionSpec functionSpec() {
        return new FunctionSpec(
                "echo",
                "image",
                null,
                Map.of(),
                null,
                1_000,
                1,
                10,
                0,
                "http://echo:8080",
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 0, 3, List.of()));
    }
}
