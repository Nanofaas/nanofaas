package it.unimib.datai.nanofaas.modules.syncqueue.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.syncqueue..",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // R5': SPI isolation. A cross-module extension is an explicit decision: add one
    // not(resideInAPackage(...)) line with a comment for every sanctioned pair
    // (ArchUnit 1.4 removed ignoreDependency from the base fluent API).
    //
    // P21 removed the one exemption this rule used to carry. The runtime-config bridge was
    // excluded by class name because RuntimeConfigExtension lived in the runtime-config module's
    // package; the contract now lives in the SPI, so the bridge depends on a contract like every
    // other consumer and no production class in this module needs an exemption.
    //
    // The rule is scoped to production classes, because the exemption it replaces was hiding a
    // second, legitimate case: this module's integration test drives the real RuntimeConfigService
    // through the module's declared testImplementation dependency. Production isolation is what
    // this rule governs, and a new cross-module production dependency still fails it.
    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
                    noClasses()
                    .that().resideInAPackage("..modules.syncqueue..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.syncqueue.."))))
                    .as("module must not depend on other modules (SPI isolation)");

    /**
     * P22: the module consumes contracts, never the core implementations behind them.
     *
     * <p>Task 8 (issue #208) made the premise this rule used to state — "the build graph already
     * makes this impossible" — false: {@code sync-queue/build.gradle} now depends on
     * {@code :control-plane} and {@code :execution-runtime} in <strong>main</strong> scope, because
     * {@link it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway}'s factory
     * (in {@code SyncQueueConfiguration}) composes the admission collaborators directly onto the
     * shared {@code it.unimib.datai.nanofaas.execution.SchedulerEngine}.
     *
     * <p>Task 10 (issue #208) moved those admission collaborators — {@code WaitEstimator},
     * {@code SyncQueueAdmissionController}, {@code SyncQueueAdmissionResult} — themselves out of
     * this module and into {@code :execution-runtime}'s {@code it.unimib.datai.nanofaas.execution
     * .admission} package, unchanged in behaviour, so the composed engine's admission path and
     * the module's own retired {@code SyncQueueService}/{@code SyncScheduler} worker (kept until
     * Task 13) share the exact same classes rather than two copies drifting apart. That move adds
     * a second, honest exception: {@code SyncQueueService} now legitimately depends on
     * {@code execution.admission..} the same way {@code SyncQueueConfiguration} always has, and is
     * named here rather than left to be caught by the regex, because the point of naming an
     * exception is that every reader can see exactly which classes carry it — widening the
     * class-name predicate, never narrowing the package regex, is the correct way to extend this
     * rule (see {@code CoreArchitectureTest}'s R6 for the same pattern applied to a
     * package-ownership rule).</p>
     */
    @ArchTest
    static final ArchRule does_not_depend_on_core_implementations =
            noClasses()
                    // The two sanctioned exceptions (see javadoc above): SyncQueueConfiguration's
                    // engineSyncQueueGateway factory composes the admission collaborators onto the
                    // engine, and SyncQueueService (Task 10) constructs and calls the same
                    // collaborators directly for its own retired, still-compiled admission path.
                    .that(DescribedPredicate.not(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("SyncQueueConfiguration")
                                    .or(com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("SyncQueueService"))))
                    .should().dependOnClassesThat()
                    .haveNameMatching("it\\.unimib\\.datai\\.nanofaas\\."
                            + "(execution\\..*"
                            + "|controlplane\\.execution\\..*"
                            + "|controlplane\\.service\\.(Metrics|InvocationService|ReactiveInvocationCoordinator"
                            + "|ExecutionCompletionHandler|RateLimiter|EngineInvocationEnqueuer|SchedulerConfiguration"
                            + "|SchedulerLifecycleAdapter)"
                            + "|controlplane\\.capacity\\.(FunctionCapacityRegistry|FunctionCapacityState|DispatchLease"
                            + "|InvocationCapacity|WaiterCapacity|ResourceQuota)"
                            + "|controlplane\\.registry\\.(FunctionRegistry|FunctionService|FunctionCatalog"
                            + "|ManagedDeploymentCoordinator)"
                            + "|controlplane\\.deployment\\.(ReplicaStatusSnapshot|DeploymentWakeUpCoordinator))")
                    .as("a module consumes contracts and ports, never core implementations, "
                            + "except SyncQueueConfiguration's EngineSyncQueueGateway factory");
}
