package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ObservationBoundaryTest {
    @Test void governorCannotRegisterOrMutateInvocationMeters() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName(
                "it.unimib.datai.nanofaas.controlplane.service.Metrics")
                .check(new ClassFileImporter().importClasses(ConcurrencyGovernor.class));
    }
}
