package it.unimib.datai.nanofaas.modules.imagevalidator.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.imagevalidator..")
class ArchitectureTest {

    // R5': IGNORATA (debito tracciato, fix in tranche futuro): image-validator dipende da
    // modules.k8s.config.KubernetesProperties (KubernetesImageValidator, ImageValidatorConfiguration,
    // 20 violazioni main+test). Decisione utente 2026-08-07: nessuno spostamento di codice ora;
    // il tranche futuro sposterà KubernetesProperties in :common o disaccoppierà. Rimuovere
    // @ArchIgnore quando risolto.
    @ArchIgnore
    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.imagevalidator..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.imagevalidator.."))))
                    .as("module must not depend on other modules (SPI isolation)");
}
