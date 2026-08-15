package it.unimib.datai.nanofaas.modules.concurrencycontrol.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.concurrencycontrol..")
class ArchitectureTest {

    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.concurrencycontrol..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.concurrencycontrol.."))))
                    .as("module must not depend on other modules (SPI isolation)");
}
