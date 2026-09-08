package it.unimib.datai.nanofaas.modules.syncqueue.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.syncqueue..")
class ArchitectureTest {

    private static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> NOT_RUNTIME_CONFIG_BRIDGE =
            new DescribedPredicate<>("not the optional runtime-config bridge") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaClass input) {
                    return !input.getName().contains("SyncQueueRuntimeConfigAutoConfiguration");
                }
            };

    // R5': SPI isolation. A cross-module extension is an explicit decision: add one
    // not(resideInAPackage(...)) line with a comment for every sanctioned pair
    // (ArchUnit 1.4 removed ignoreDependency from the base fluent API).
    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
                    noClasses()
                    .that().resideInAPackage("..modules.syncqueue..")
                    .and(NOT_RUNTIME_CONFIG_BRIDGE)
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.syncqueue.."))))
                    .as("module must not depend on other modules (SPI isolation)");
}
