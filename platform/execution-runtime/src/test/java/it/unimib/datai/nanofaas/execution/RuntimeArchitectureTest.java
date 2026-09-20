package it.unimib.datai.nanofaas.execution;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * The execution-runtime library is the mandatory home for execution ownership (issue #208, Task
 * 9): the store, capacity and input classes that used to live in {@code :control-plane}. It must
 * stay independent of Spring, of Kubernetes (fabric8) and of every optional queue module, so a
 * minimal deployment can compile and run without any of them, and so the module dependency
 * direction can never invert back toward the control plane it is extracted from.
 *
 * <p>This must fail loudly, not silently, if the imported package set is ever empty (a moved
 * class renamed out from under the import, or a build misconfiguration importing nothing) — an
 * ArchUnit rule over zero classes is vacuously true and would stop meaning anything.
 */
class RuntimeArchitectureTest {

    private static final String[] EXECUTION_PACKAGES = {
        "it.unimib.datai.nanofaas.execution",
        "it.unimib.datai.nanofaas.controlplane.execution",
        "it.unimib.datai.nanofaas.controlplane.capacity",
        "it.unimib.datai.nanofaas.controlplane.input"
    };

    @Test
    void executionRuntimeDoesNotDependOnSpringFabric8OrQueueModules() {
        JavaClasses importedClasses = new ClassFileImporter().importPackages(EXECUTION_PACKAGES);

        assertThat(importedClasses.size())
                .as("the execution-runtime packages must actually be on the analyzed classpath; "
                        + "an empty import makes this rule vacuously true")
                .isGreaterThan(50);
        assertThat(importedClasses.stream().anyMatch(c -> c.getSimpleName().equals("ExecutionRecord")))
                .as("ExecutionRecord must be among the imported classes")
                .isTrue();

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..", "io.fabric8..", "it.unimib.datai.nanofaas.modules..")
                .check(importedClasses);
    }
}
