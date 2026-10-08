package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            .importUrl(FunctionCatalogView.class.getProtectionDomain().getCodeSource().getLocation());

    @Test
    void theSpiReferencesOnlyItsOwnTypesTheWireModelsAndTwoNeutralLibraries() {
        purityRule().check(spiClasses);
    }

    private com.tngtech.archunit.lang.ArchRule purityRule() {
        assertTrue(spiClasses.contain(FunctionCatalogView.class.getName()), "SPI artifact must have subjects");
        var ownTypes = new DescribedPredicate<JavaClass>("belong to the SPI artifact") {
            @Override
            public boolean test(JavaClass type) {
                return spiClasses.contain(type.getBaseComponentType().getName());
            }
        };
        return classes().should().onlyDependOnClassesThat(ownTypes.or(
                JavaClass.Predicates.resideInAnyPackage(
                        "it.unimib.datai.nanofaas.common..", "java..", "javax..",
                        "reactor.core..", "reactor.util..", "org.slf4j..", "org.reactivestreams..")))
                .as("the SPI depends only on its own artifact, common, reactor-core and slf4j");
    }

    static final class ForeignImplementation { }
    static final class SpiConsumerFixture { ForeignImplementation implementation; }

    @Test
    void rejectsForeignTypesInTheControlplaneNamespace() {
        var result = purityRule().evaluate(new ClassFileImporter().importClasses(SpiConsumerFixture.class));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().getDetails().toString().contains("ForeignImplementation"));
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
