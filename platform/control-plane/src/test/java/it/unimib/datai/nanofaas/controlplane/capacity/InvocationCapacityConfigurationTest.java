package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationCapacityConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(FunctionCapacityRegistry.class)
            .withUserConfiguration(InvocationCapacityConfiguration.class);

    @Test
    void invalidBoundPropertiesAbortContextStartup() {
        contextRunner
                .withPropertyValues(
                        "nanofaas.invocation-capacity.executions-global=1",
                        "nanofaas.invocation-capacity.executions-per-function=2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "executions-per-function must not exceed executions-global");
                });
    }

    @Test
    void configuredExecutionCanonicalPhysicalAndWaiterOwnersAllDrainToZero() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            FunctionCapacityRegistry generations = context.getBean(FunctionCapacityRegistry.class);
            generations.register("fn", 1);
            FunctionGeneration generation = generations.activeGeneration("fn");
            InvocationCapacity invocations = context.getBean(InvocationCapacity.class);
            WaiterCapacity waiters = context.getBean(WaiterCapacity.class);

            InvocationCapacity.Admission admission = invocations.reserve("fn", "e1", 128);
            admission.publish();
            ResourceQuota.Reservation copy = invocations.reserveInputCopy(
                    generation, new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "e1/copy"), 128);
            WaiterCapacity.Waiter waiter = waiters.reserve(generation, "e1");

            waiter.close();
            copy.close();
            admission.logicalExecution().close();
            admission.canonicalInput().close();

            assertThat(invocations.executionReservedGlobally()).isZero();
            assertThat(invocations.inputReservedGlobally()).isZero();
            assertThat(invocations.physicalInputCopyReservedGlobally()).isZero();
            assertThat(waiters.reservedGlobally()).isZero();
        });
    }
}
