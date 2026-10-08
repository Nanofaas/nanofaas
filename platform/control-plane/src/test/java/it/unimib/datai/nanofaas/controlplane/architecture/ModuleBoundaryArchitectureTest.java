package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleBoundaryArchitectureTest {
    private static final String ROOT = "it.unimib.datai.nanofaas.";
    private static final String SYNC_CONFIGURATION = ROOT + "modules.syncqueue.SyncQueueConfiguration";
    private static final String ENQUEUER = ROOT + "controlplane.service.EngineInvocationEnqueuer";
    private static final Set<String> SYNC_TARGETS = Set.of(
            ROOT + "controlplane.service.EngineSyncQueueGateway",
            ENQUEUER + "$AdmissionProfile",
            ENQUEUER,
            ROOT + "execution.SchedulerEngine",
            ROOT + "execution.PendingWorkStore",
            ROOT + "execution.admission.SyncQueueAdmissionController",
            ROOT + "execution.admission.WaitEstimator");
    private static final Map<String, String> MODULE_PACKAGES = Map.ofEntries(
            Map.entry("async-queue", ROOT + "modules.asyncqueue"),
            Map.entry("sync-queue", ROOT + "modules.syncqueue"),
            Map.entry("autoscaler", ROOT + "modules.autoscaler"),
            Map.entry("concurrency-control", ROOT + "modules.concurrencycontrol"),
            Map.entry("runtime-config", ROOT + "modules.runtimeconfig"),
            Map.entry("build-metadata", ROOT + "modules.buildmetadata"),
            Map.entry("offload", ROOT + "modules.offload"),
            Map.entry("p2p-discovery", ROOT + "modules.p2pdiscovery"),
            Map.entry("forecasting", ROOT + "modules.forecasting"),
            Map.entry("k8s-deployment-provider", ROOT + "modules.k8s"),
            Map.entry("container-deployment-provider", ROOT + "modules.containerdeploymentprovider"),
            Map.entry("containerd-deployment-provider", ROOT + "modules.containerddeploymentprovider"));

    @Test
    void selectedProductionModulesUseOnlyContractsAndSharedLibraries() {
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        for (String marker : Set.of(ROOT + "controlplane.api.FunctionController",
                ROOT + "controlplane.registry.FunctionCatalogView",
                ROOT + "controlplane.execution.ExecutionRecord")) {
            assertThat(production.contain(marker)).as("mandatory artifact marker %s", marker).isTrue();
        }
        String selection = System.getProperty("nanofaas.selectedControlPlaneModules");
        Set<String> selected = selection == null
                ? production.stream().map(ModuleBoundaryArchitectureTest::moduleOf)
                        .filter(id -> id != null).collect(Collectors.toSet())
                : Arrays.stream(selection.split(",")).filter(id -> !id.isBlank()).collect(Collectors.toSet());
        assertSelectedModulesPresent(production, selected);
        optionalModulesUseContractsOnly().check(production);
    }

    static ArchRule optionalModulesUseContractsOnly() {
        return classes().that(new DescribedPredicate<>("are optional module production classes") {
            @Override
            public boolean test(JavaClass item) {
                return item.getPackageName().startsWith(ROOT + "modules.");
            }
        }).should(moduleDependencyCondition()).allowEmptyShould(true);
    }

    static ArchCondition<JavaClass> moduleDependencyCondition() {
        return new ArchCondition<>("depend on contracts and shared libraries, with explicit composition pairs") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (var dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    String targetModule = moduleOf(target);
                    boolean sibling = targetModule != null && !targetModule.equals(moduleOf(origin));
                    var source = target.getSource().map(Source::getUri).orElse(null);
                    boolean implementation = CoreArchitectureTest.isCoreSource(source)
                            || CoreArchitectureTest.isRuntimeSource(source);
                    boolean unresolved = source == null && (target.getName().startsWith(ROOT + "controlplane.")
                            || target.getName().startsWith(ROOT + "execution."));
                    boolean approved = isApprovedCompositionPair(origin.getName(), target.getName());
                    // The enclosing type appears in enum metadata. A real access to it is forbidden.
                    if (approved && target.getName().equals(ENQUEUER)) {
                        approved = dependency.getDescription().startsWith("Class <" + origin.getName()
                                + "> depends on <" + ENQUEUER + ">")
                                && origin.getAccessesFromSelf().stream()
                                .noneMatch(access -> access.getTargetOwner().getName().equals(ENQUEUER));
                    }
                    if (sibling || unresolved || (implementation && !approved)) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                dependency.getDescription() + (unresolved ? " (internal target source unresolved)" : "")));
                    }
                }
            }
        };
    }

    static boolean isApprovedCompositionPair(String originName, String targetName) {
        return originName.equals(SYNC_CONFIGURATION) && SYNC_TARGETS.contains(targetName);
    }

    static void assertSelectedModulesPresent(JavaClasses subjects, Set<String> selectedModules) {
        for (String id : selectedModules) {
            assertThat(MODULE_PACKAGES).as("known selected module %s", id).containsKey(id);
            assertThat(subjects.stream().anyMatch(type -> id.equals(moduleOf(type))))
                    .as("production subjects for selected module %s", id).isTrue();
        }
        for (JavaClass subject : subjects) {
            if (subject.getPackageName().startsWith(ROOT + "modules.")) {
                assertThat(moduleOf(subject)).as("known module package for %s", subject.getName()).isNotNull();
            }
        }
    }

    private static String moduleOf(JavaClass type) {
        return MODULE_PACKAGES.entrySet().stream()
                .filter(entry -> type.getPackageName().equals(entry.getValue())
                        || type.getPackageName().startsWith(entry.getValue() + "."))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    static final class IllegalCoreFixture { FunctionService service; }
    static final class IllegalRuntimeFixture { ExecutionStore store; }
    static final class AllowedContractFixture {
        FunctionCatalogView catalog;
        ManagedDeploymentProvider provider;
    }
    static final class SyncQueueConfiguration { EngineSyncQueueGateway gateway; }

    @Test
    void rejectsCoreImplementationDependencies() {
        assertRejected(IllegalCoreFixture.class, "FunctionService");
    }

    @Test
    void rejectsRuntimeImplementationDependencies() {
        assertRejected(IllegalRuntimeFixture.class, "ExecutionStore");
    }

    @Test
    void acceptsExistingContracts() {
        var subjects = new ClassFileImporter().importClasses(AllowedContractFixture.class);
        assertThat(classes().should(moduleDependencyCondition()).evaluate(subjects).hasViolation()).isFalse();
    }

    @Test
    void rejectsConfigurationNameImpostor() {
        assertRejected(SyncQueueConfiguration.class, "EngineSyncQueueGateway");
        assertThat(isApprovedCompositionPair("it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration",
                FunctionService.class.getName())).isFalse();
    }

    @Test
    void rejectsMissingSelectedModulesAndUnknownIds() {
        var subjects = new ClassFileImporter().importClasses(AllowedContractFixture.class);
        assertThatThrownBy(() -> assertSelectedModulesPresent(subjects, Set.of("sync-queue")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("sync-queue");
        assertThatThrownBy(() -> assertSelectedModulesPresent(subjects, Set.of("unknown-module")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("unknown-module");
    }

    @Test
    void acceptsCoreOnlySelection() {
        assertSelectedModulesPresent(new ClassFileImporter().importClasses(AllowedContractFixture.class), Set.of());
    }

    private static void assertRejected(Class<?> fixture, String target) {
        var subjects = new ClassFileImporter().importClasses(fixture);
        var result = classes().should(moduleDependencyCondition()).evaluate(subjects);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails().toString()).contains(fixture.getSimpleName(), target);
    }
}
