package it.unimib.datai.nanofaas.modules.forecasting;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
class ForecastingArchitectureTest {
    @Test void optionalModuleDoesNotDependOnOtherImplementations() {
        var classes = new ClassFileImporter().importPackages("it.unimib.datai.nanofaas.modules.forecasting");
        noClasses().that().resideInAPackage("..modules.forecasting..")
                .should().dependOnClassesThat().resideInAnyPackage("..modules.offload..", "..modules.p2pdiscovery..")
                .check(classes);
    }
}
