package it.unimib.datai.nanofaas.modules.k8s.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.k8s..")
class ArchitectureTest {

    // R5': isolamento SPI. Un'estensione tra moduli è una decisione esplicita:
    // aggiungere una riga not(resideInAPackage(...)) con commento per ogni coppia sanzionata
    // (ArchUnit 1.4 ha rimosso ignoreDependency dal fluent API di base).
    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.k8s..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.k8s.."))))
                    .as("module must not depend on other modules (SPI isolation)");
}
