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
     * (in {@code SyncQueueConfiguration}) composes this module's own admission collaborators
     * (WaitEstimator, SyncQueueAdmissionController) directly onto the shared
     * {@code it.unimib.datai.nanofaas.execution.SchedulerEngine} — the one sanctioned exception,
     * confined to that single factory method, not the whole module. This rule is now the actual
     * enforcement, not a second line of defence behind an impossible build graph: it is expressed
     * by type name (and, for the execution package, by package name) rather than only by package
     * because the core and the contract library deliberately share package names —
     * {@code controlplane.service} holds both the {@code InvocationEnqueuer} contract and the
     * {@code Metrics} implementation.</p>
     */
    @ArchTest
    static final ArchRule does_not_depend_on_core_implementations =
            noClasses()
                    // The one sanctioned exception (see javadoc above): SyncQueueConfiguration's
                    // engineSyncQueueGateway factory is the sole place this module is allowed to
                    // reach into the composed engine.
                    .that(DescribedPredicate.not(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("SyncQueueConfiguration")))
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
