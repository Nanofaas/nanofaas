package it.unimib.datai.nanofaas.modules.autoscaler;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ObservationBoundaryTest {
    @Test void observationReaderCannotRegisterOrMutateInvocationMeters() {
        noClasses().should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .check(new ClassFileImporter().importClasses(ScalingMetricsReader.class));
    }
}
