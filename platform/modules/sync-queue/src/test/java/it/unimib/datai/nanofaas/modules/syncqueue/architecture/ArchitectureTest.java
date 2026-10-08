package it.unimib.datai.nanofaas.modules.syncqueue.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.regex.Pattern;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

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
     * The module consumes contracts; SyncQueueConfiguration is the composition point that binds
     * admission collaborators to SchedulerEngine.
     */
    private static final String ROOT = "it.unimib.datai.nanofaas.";
    private static final String ENQUEUER = ROOT + "controlplane.service.EngineInvocationEnqueuer";
    private static final Set<String> COMPOSITION_TARGETS = Set.of(
            ROOT + "controlplane.service.EngineSyncQueueGateway", ENQUEUER, ENQUEUER + "$AdmissionProfile",
            ROOT + "execution.SchedulerEngine", ROOT + "execution.PendingWorkStore",
            ROOT + "execution.admission.SyncQueueAdmissionController", ROOT + "execution.admission.WaitEstimator");
    private static final Pattern CORE_IMPLEMENTATION = Pattern.compile(
            "it\\.unimib\\.datai\\.nanofaas\\."
                    + "(execution\\..*|controlplane\\.execution\\..*"
                    + "|controlplane\\.service\\.(Metrics|InvocationService|ReactiveInvocationCoordinator"
                    + "|ExecutionCompletionHandler|RateLimiter|EngineInvocationEnqueuer(?:\\$.*)?|EngineSyncQueueGateway"
                    + "|SchedulerConfiguration|SchedulerLifecycleAdapter)"
                    + "|controlplane\\.capacity\\.(FunctionCapacityRegistry|FunctionCapacityState|DispatchLease"
                    + "|InvocationCapacity|WaiterCapacity|ResourceQuota)"
                    + "|controlplane\\.registry\\.(FunctionRegistry|FunctionService|FunctionCatalog|ManagedDeploymentCoordinator)"
                    + "|controlplane\\.deployment\\.(ReplicaStatusSnapshot|DeploymentWakeUpCoordinator))");

    @ArchTest
    static final ArchRule does_not_depend_on_core_implementations = classes()
            .should(new ArchCondition<JavaClass>("consume contracts except the exact sync composition pairs") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    for (var dependency : origin.getDirectDependenciesFromSelf()) {
                        String target = dependency.getTargetClass().getName();
                        boolean approved = origin.getName().equals(ROOT + "modules.syncqueue.SyncQueueConfiguration")
                                && COMPOSITION_TARGETS.contains(target);
                        if (approved && target.equals(ENQUEUER)) {
                            approved = dependency.getDescription().startsWith("Class <" + origin.getName()
                                + "> depends on <" + ENQUEUER + ">")
                                && origin.getAccessesFromSelf().stream()
                                    .noneMatch(access -> access.getTargetOwner().getName().equals(ENQUEUER));
                        }
                        if (CORE_IMPLEMENTATION.matcher(target).matches() && !approved) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                }
            });
}
