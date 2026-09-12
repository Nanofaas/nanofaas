package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The contract library holds contracts, and nothing a contract cannot legally name.
 *
 * <p>The primary guard for this is the build graph: this project's compile classpath contains
 * {@code :common}, reactor-core and slf4j and nothing else, so a reference to a core
 * implementation does not compile. These rules exist for the case the build file changes — an
 * added dependency would silently widen what the SPI may reference, and a whitelist notices that
 * where a package-name rule could not, because core and SPI deliberately share package names.</p>
 */
class SpiPurityTest {

    private final JavaClasses spiClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("it.unimib.datai.nanofaas");

    @Test
    void theSpiReferencesOnlyItsOwnTypesTheWireModelsAndTwoNeutralLibraries() {
        classes().should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        // The SPI's own contracts; every one of them is in the control-plane
                        // namespace, including the runtime-config contract two modules implement.
                        "it.unimib.datai.nanofaas.controlplane..",
                        // Shared wire and runtime models: the SPI may depend on common, never the
                        // reverse.
                        "it.unimib.datai.nanofaas.common..",
                        "java..",
                        "javax..",
                        // A gateway signature returns a Mono; two support classes log.
                        "reactor.core..",
                        "reactor.util..",
                        "org.slf4j..",
                        "org.reactivestreams..")
                .as("the SPI depends only on common, reactor-core and slf4j")
                .check(spiClasses);
    }

    @Test
    void theSpiCarriesNoFrameworkOrInfrastructureDependency() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.github.benmanes..",
                        "io.micrometer..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..",
                        "io.fabric8..",
                        "com.github.dockerjava..")
                .as("the SPI holds no Spring, autoconfiguration, cache, metrics, JSON or provider client")
                .check(spiClasses);
    }

    @Test
    void theSpiNeverDependsOnAnOptionalModuleImplementation() {
        // No enumeration of module packages: the whole namespace is off limits, which also holds
        // for a module added later. P21 emptied this namespace of contracts - the runtime-config
        // contract moved into the control-plane namespace precisely so that no contract sits in a
        // package named after one of the modules implementing it.
        noClasses().should().dependOnClassesThat()
                .resideInAPackage("it.unimib.datai.nanofaas.modules..")
                .as("a contract never points at the module that implements it")
                .check(spiClasses);
    }
}
