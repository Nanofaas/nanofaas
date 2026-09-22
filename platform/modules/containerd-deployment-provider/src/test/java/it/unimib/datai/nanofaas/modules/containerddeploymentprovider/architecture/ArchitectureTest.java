package it.unimib.datai.nanofaas.modules.containerddeploymentprovider.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.containerddeploymentprovider..",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.containerddeploymentprovider..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage(
                                            "..modules.containerddeploymentprovider.."))))
                    .as("module must not depend on other modules (SPI isolation)");

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
