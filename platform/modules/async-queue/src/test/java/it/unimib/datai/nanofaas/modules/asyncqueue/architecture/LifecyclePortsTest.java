package it.unimib.datai.nanofaas.modules.asyncqueue.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class LifecyclePortsTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("it.unimib.datai.nanofaas.modules.asyncqueue");

    @Test
    void schedulersDispatchWithoutTheHttpOrchestrator() {
        noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("it.unimib.datai.nanofaas.controlplane.service.InvocationService")
                .check(classes);
    }

    @Test
    void queuesCannotConcludeMutableExecutions() {
        noClasses().should().dependOnClassesThat()
                .resideInAPackage("it.unimib.datai.nanofaas.controlplane.execution..")
                .check(classes);
    }

    @Test
    void consumersCannotAccessConcreteCapacityOwners() {
        noClasses().should().dependOnClassesThat()
                .haveNameMatching(".*\\.(DispatchLease|FunctionCapacityState)")
                .check(classes);
    }
}
