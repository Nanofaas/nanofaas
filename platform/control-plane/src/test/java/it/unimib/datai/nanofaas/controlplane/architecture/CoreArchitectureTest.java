package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composition-level architecture rules for the scheduler work (issue #208, Task 13).
 *
 * <p>The analyzed packages include the optional modules, not only the core: a scheduling
 * strategy is contributed by a module, and its dependency direction is the one thing the build
 * graph of this module cannot see (the core depends on the modules at runtime only).
 *
 * <p>Widening the import set widens the two rules that select their subjects by <em>exclusion</em>
 * rather than by package: R2 ({@code resideOutsideOfPackage("..api..")}) and P22
 * ({@code resideOutsideOfPackage("..execution..")}) now also have module classes in their scope.
 * They are green with them today, and that is the strict direction — a module that trips one is
 * looking at a rule the core already obeys — but it is a widening, not a no-op: a module author
 * can now hit a rule the module's own architecture test never mentions.
 */
@AnalyzeClasses(packages = {
        "it.unimib.datai.nanofaas.controlplane..",
        "it.unimib.datai.nanofaas.modules.."})
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

    /**
     * P22: only the execution package may publish a terminal state.
     *
     * <p>{@code ExecutionRecord.beginSettlement} and {@code publishTerminal} are the two calls that
     * actually make an outcome final. Everything else — the completion handler, the queue modules
     * through {@code QueueLifecycle}, the offload gateway, the administrative-expiry path — asks
     * the owner through {@code ExecutionStore.settle}, which delegates to the single
     * {@code ExecutionLifecycle}. A second caller of these two methods would be a second terminal
     * owner, which is exactly what invariants I1 and I3 forbid: two owners can disagree about the
     * one result an execution is allowed to have.</p>
     */
    @ArchTest
    static final ArchRule only_the_execution_owner_publishes_a_terminal_state =
            noClasses()
                    .that().resideOutsideOfPackage("..controlplane.execution..")
                    .should().callMethodWhere(
                            com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                    com.tngtech.archunit.core.domain.properties.HasName.Predicates
                                            .nameMatching("beginSettlement|publishTerminal"))
                                    .and(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                            com.tngtech.archunit.core.domain.properties.HasOwner.Predicates
                                                    .With.owner(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                                            .assignableTo("it.unimib.datai.nanofaas.controlplane"
                                                                    + ".execution.ExecutionRecord")))))
                    .as("only the execution package may publish a terminal state");

    // R6: the controlplane namespace belongs to the core, to the mandatory contract library, and
    // to the mandatory execution runtime, and to nothing else. P21 split it deliberately: the
    // contracts the optional modules compile against moved to :control-plane-spi under their
    // existing names, so that AOT hints, reflect-config, package rules and module descriptors keep
    // addressing them as before. Task 9 (issue #208) split it further: execution ownership (the
    // store, capacity and input classes) moved into :execution-runtime, the mandatory library that
    // owns lifecycle/resources/pending work regardless of which scheduler strategy is active,
    // again under their existing names for the same reason. An optional module, an SDK or a
    // service claiming a package in this namespace is still a violation, which is what this rule
    // exists to catch.
    @ArchTest
    static final ArchRule controlplane_namespace_is_owned_by_core =
            classes()
                    .that().resideInAPackage("it.unimib.datai.nanofaas.controlplane..")
                    .should(haveSourceInCoreOrContractModule())
                    .as("classes in the controlplane namespace must live in the control-plane "
                            + "module, its mandatory contract library, or its mandatory execution runtime");

    private static ArchCondition<JavaClass> haveSourceInCoreOrContractModule() {
        return new ArchCondition<>("have their class file under the control-plane module, its "
                + "contract library or its execution runtime") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // ArchUnit 1.4.1 dropped SourceCodeLocation's source-path accessor, so read the
                // source URI instead; it can point to either a classes directory or a JAR.
                URI source = item.getSource().map(Source::getUri).orElse(null);
                if (source == null
                        || !(isCoreSource(source) || isContractSource(source) || isRuntimeSource(source))) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getDescription() + " lives in neither the control-plane module, its"
                                    + " contract library, nor its execution runtime (" + source + ")"));
                }
            }
        };
    }

    /**
     * A strategy implements a scheduling POLICY and nothing else: it holds no threads, no meters,
     * no callbacks and no store, so the engine can swap one for another without either side
     * knowing. That is what the {@code SchedulingIndex} contract already promises in prose; this
     * rule is the bytecode check behind it, and it is the one direction the build graph cannot
     * see from here — {@code :control-plane} has the queue modules on its RUNTIME classpath only,
     * so the modules' own {@code ArchitectureTest}s (which name the core implementations they
     * refuse) are the other half.
     *
     * <p>Whitelisted rather than blacklisted, and by package rather than by class: a whitelist
     * notices a dependency someone adds later, where a list of forbidden names only notices the
     * ones that were already there. See {@link #scheduling_policy_dependencies()} for exactly what
     * is allowed.
     *
     * <p>What this forbids in particular: the engine and its mutable store
     * ({@code ..controlplane.execution}, {@code it.unimib.datai.nanofaas.execution}), which would
     * be a cycle the moment the engine switched strategies, and Spring, which would mean a
     * strategy registered by annotation scanning or reflection instead of being handed to
     * {@code StrategyRegistry}'s constructor.
     */
    @ArchTest
    static final ArchRule scheduling_policies_depend_on_the_spi_alone =
            classes().that(scheduling_policy_types())
                    .should().onlyDependOnClassesThat(scheduling_policy_dependencies())
                    .as("a scheduling strategy depends on the scheduling SPI and the values it "
                            + "names, never on the runtime, a mutable store or Spring")
                    // A core-only profile selects no queue module, hence no strategy; the subjects
                    // guard below pins them per profile, so an empty set here is checked, not silent.
                    .allowEmptyShould(true);

    /**
     * What a policy is allowed to name: the SPI contracts it implements, the value types those
     * contracts carry ({@code FunctionGeneration}), the JDK — and the indexes it is a factory for,
     * which is how {@code newIndex()} constructs its own nested index. Naming the indexes rather
     * than the modules they live in keeps the rest of each module out (a queue manager, a gateway,
     * a Spring configuration are all still refused), and it stays correct for a strategy added
     * later without anyone having to extend a package list.
     */
    private static DescribedPredicate<JavaClass> scheduling_policy_dependencies() {
        return resideInAPackage("it.unimib.datai.nanofaas.controlplane.scheduler..")
                .or(resideInAPackage("it.unimib.datai.nanofaas.controlplane.capacity.."))
                .or(resideInAPackage("java.."))
                .or(resideInAPackage("javax.."))
                .or(assignableTo("it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex")
                        .and(resideInAPackage("it.unimib.datai.nanofaas.modules..")))
                .as("the scheduling SPI, the values it names, the JDK, and the indexes a policy builds");
    }

    private static DescribedPredicate<JavaClass> scheduling_policy_types() {
        return assignableTo("it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy")
                .or(assignableTo("it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex"))
                // The SPI's own package is where those contracts live; the rule is about the
                // implementations of them.
                .and(not(resideInAPackage("it.unimib.datai.nanofaas.controlplane.scheduler..")))
                .as("types implementing the scheduling SPI outside the SPI's own package");
    }

    /**
     * An ArchUnit rule over an empty (or partial) import set is vacuously true, which is exactly
     * how a rule like the one above stops meaning anything: a package renamed out from under the
     * {@code @AnalyzeClasses} list, or a module dropped from the classpath, would leave it green
     * and silent. This pins the SUBJECTS, not just the import: one strategy and the index it
     * builds, per queue module the profile selected. A new strategy fails here until it is added,
     * which is the reviewable step this guard is for.
     */
    @ArchTest
    static void the_scheduling_policy_rule_has_exactly_the_subjects_the_profile_selected(JavaClasses classes) {
        String selected = System.getProperty("nanofaas.selectedControlPlaneModules");
        if (selected == null) {
            // Not a Gradle-profile run (an IDE run of the class alone): nothing to compare to.
            return;
        }
        List<String> expected = new ArrayList<>();
        if (selected.contains("async-queue")) {
            expected.addAll(List.of("PerFunctionSchedulingStrategy", "PerFunctionIndex"));
        }
        if (selected.contains("sync-queue")) {
            expected.addAll(List.of("SharedQueueSchedulingStrategy", "SharedQueueIndex"));
        }

        assertThat(classes.stream()
                .filter(scheduling_policy_types())
                .map(JavaClass::getSimpleName)
                .toList())
                .as("the strategy rule's subjects for the selected profile [%s]", selected)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static boolean isCoreSource(URI uri) {
        return uri.toString().contains("/platform/control-plane/");
    }

    static boolean isContractSource(URI uri) {
        return uri.toString().contains("/platform/control-plane-spi/");
    }

    static boolean isRuntimeSource(URI uri) {
        return uri.toString().contains("/platform/execution-runtime/");
    }
}
