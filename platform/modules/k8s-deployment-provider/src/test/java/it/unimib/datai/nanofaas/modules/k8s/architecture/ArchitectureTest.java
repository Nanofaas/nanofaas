package it.unimib.datai.nanofaas.modules.k8s.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.k8s..",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // R5': SPI isolation. A cross-module extension is an explicit decision: add one
    // not(resideInAPackage(...)) line with a comment for every sanctioned pair
    // (ArchUnit 1.4 removed ignoreDependency from the base fluent API).
    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.k8s..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.k8s.."))))
                    .as("module must not depend on other modules (SPI isolation)");

    /**
     * P22: the module consumes contracts, never the core implementations behind them.
     *
     * <p>The build graph already makes this impossible — this module's compile classpath holds the
     * contract library and not {@code :control-plane} — so the rule is a second line of defence
     * that names what must stay out if that dependency is ever added back. It is expressed by type
     * name rather than by package because the core and the contract library deliberately share
     * package names: {@code controlplane.service} holds both the {@code InvocationEnqueuer}
     * contract and the {@code Metrics} implementation.</p>
     */
    @ArchTest
    static final ArchRule does_not_depend_on_core_implementations =
            noClasses()
                    .should().dependOnClassesThat()
                    .haveNameMatching("it\\.unimib\\.datai\\.nanofaas\\.controlplane\\."
                            + "(execution\\..*"
                            + "|service\\.(Metrics|InvocationService|ReactiveInvocationCoordinator"
                            + "|ExecutionCompletionHandler|RateLimiter)"
                            + "|capacity\\.(FunctionCapacityRegistry|FunctionCapacityState|DispatchLease"
                            + "|InvocationCapacity|WaiterCapacity|ResourceQuota)"
                            + "|registry\\.(FunctionRegistry|FunctionService|FunctionCatalog"
                            + "|ManagedDeploymentCoordinator)"
                            + "|deployment\\.(ReplicaStatusSnapshot|DeploymentWakeUpCoordinator))")
                    .as("a module consumes contracts and ports, never core implementations");
}
