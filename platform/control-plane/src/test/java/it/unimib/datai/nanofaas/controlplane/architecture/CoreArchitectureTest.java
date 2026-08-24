package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.net.URI;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.controlplane..")
class CoreArchitectureTest {

    // R1: il core non deve contenere cicli tra i suoi package top-level.
    @ArchTest
    static final ArchRule core_packages_are_free_of_cycles =
            slices().matching("..controlplane.(*)..").should().beFreeOfCycles();

    // R2: api è l'entry point: le classi fuori da api non devono dipendere da api.
    @ArchTest
    static final ArchRule api_is_the_only_entry_point =
            noClasses()
                    .that().resideOutsideOfPackage("..controlplane.api..")
                    .should().dependOnClassesThat().resideInAPackage("..controlplane.api..")
                    .as("the api layer is the entry point: lower layers must not reach back into it");

    // R3: il core non dipende mai dai moduli (optionalità SPI: il core funziona senza).
    @ArchTest
    static final ArchRule core_does_not_depend_on_modules =
            noClasses()
                    .that().resideInAPackage("..controlplane..")
                    .should().dependOnClassesThat().resideInAPackage("..modules..")
                    .as("the core must work without optional modules (SPI)");

    // R4: la direzione è api -> service -> dispatch: i layer bassi non risalgono a service.
    @ArchTest
    static final ArchRule lower_layers_do_not_depend_on_service =
            noClasses()
                    .that().resideInAnyPackage(
                            "..controlplane.dispatch..",
                            "..controlplane.execution..",
                            "..controlplane.deployment..")
                    .should().dependOnClassesThat().resideInAPackage("..controlplane.service..")
                    .as("dispatch, execution and deployment must not depend on service");

    // R6: il namespace controlplane appartiene al core — le classi che vi risiedono
    // devono avere il class file sotto platform/control-plane/.
    @ArchTest
    static final ArchRule controlplane_namespace_is_owned_by_core =
            classes()
                    .that().resideInAPackage("it.unimib.datai.nanofaas.controlplane..")
                    .should(haveSourceInCoreModule())
                    .as("classes in the controlplane namespace must live in the control-plane module");

    private static ArchCondition<JavaClass> haveSourceInCoreModule() {
        return new ArchCondition<>("have their class file under the control-plane module") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // ArchUnit 1.4.1 dropped SourceCodeLocation's source-path accessor, so read the
                // source URI instead; it can point to either a classes directory or a JAR.
                URI source = item.getSource().map(Source::getUri).orElse(null);
                if (source == null || !isCoreSource(source)) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getDescription() + " does not live in the control-plane module (" + source + ")"));
                }
            }
        };
    }

    static boolean isCoreSource(URI uri) {
        return uri.toString().contains("/platform/control-plane/");
    }
}
