package it.unimib.datai.nanofaas.modules.forecasting;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ForecastingArchitectureTest {
    @Test
    void optionalModuleDoesNotDependOnOtherImplementations() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("it.unimib.datai.nanofaas.modules.forecasting");
        noClasses().that().resideInAPackage("..modules.forecasting..")
                .should().dependOnClassesThat(resideInAPackage("..modules..")
                        .and(DescribedPredicate.not(resideInAPackage("..modules.forecasting.."))))
                .check(classes);
    }
}
