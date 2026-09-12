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

    // R1: the core must contain no cycles between its top-level packages.
    @ArchTest
    static final ArchRule core_packages_are_free_of_cycles =
            slices().matching("..controlplane.(*)..").should().beFreeOfCycles();

    // R2: api is the entry point: classes outside api must not depend on api.
    @ArchTest
    static final ArchRule api_is_the_only_entry_point =
            noClasses()
                    .that().resideOutsideOfPackage("..controlplane.api..")
                    .should().dependOnClassesThat().resideInAPackage("..controlplane.api..")
                    .as("the api layer is the entry point: lower layers must not reach back into it");

    // R3: the core never depends on the modules (SPI optionality: the core works without them).
    @ArchTest
    static final ArchRule core_does_not_depend_on_modules =
            noClasses()
                    .that().resideInAPackage("..controlplane..")
                    .should().dependOnClassesThat().resideInAPackage("..modules..")
                    .as("the core must work without optional modules (SPI)");

    // R4: the direction is api -> service -> dispatch: lower layers must not reach back into service.
    @ArchTest
    static final ArchRule lower_layers_do_not_depend_on_service =
            noClasses()
                    .that().resideInAnyPackage(
                            "..controlplane.dispatch..",
                            "..controlplane.execution..",
                            "..controlplane.deployment..")
                    .should().dependOnClassesThat().resideInAPackage("..controlplane.service..")
                    .as("dispatch, execution and deployment must not depend on service");

    // R6: the controlplane namespace belongs to the core and to the mandatory contract library,
    // and to nothing else. P21 split it deliberately: the contracts the optional modules compile
    // against moved to :control-plane-spi under their existing names, so that AOT hints,
    // reflect-config, package rules and module descriptors keep addressing them as before. An
    // optional module, an SDK or a service claiming a package in this namespace is still a
    // violation, which is what this rule exists to catch.
    @ArchTest
    static final ArchRule controlplane_namespace_is_owned_by_core =
            classes()
                    .that().resideInAPackage("it.unimib.datai.nanofaas.controlplane..")
                    .should(haveSourceInCoreOrContractModule())
                    .as("classes in the controlplane namespace must live in the control-plane "
                            + "module or in its mandatory contract library");

    private static ArchCondition<JavaClass> haveSourceInCoreOrContractModule() {
        return new ArchCondition<>("have their class file under the control-plane module or its contract library") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // ArchUnit 1.4.1 dropped SourceCodeLocation's source-path accessor, so read the
                // source URI instead; it can point to either a classes directory or a JAR.
                URI source = item.getSource().map(Source::getUri).orElse(null);
                if (source == null || !(isCoreSource(source) || isContractSource(source))) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getDescription() + " lives in neither the control-plane module nor its"
                                    + " contract library (" + source + ")"));
                }
            }
        };
    }

    static boolean isCoreSource(URI uri) {
        return uri.toString().contains("/platform/control-plane/");
    }

    static boolean isContractSource(URI uri) {
        return uri.toString().contains("/platform/control-plane-spi/");
    }
}
