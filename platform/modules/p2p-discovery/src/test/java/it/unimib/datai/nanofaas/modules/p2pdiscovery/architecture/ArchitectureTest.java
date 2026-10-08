package it.unimib.datai.nanofaas.modules.p2pdiscovery.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.p2pdiscovery..",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.p2pdiscovery..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.p2pdiscovery.."))))
                    .as("module must not depend on other modules (SPI isolation)");

    @ArchTest
    static final ArchRule only_peer_cluster_touches_scalecube =
            noClasses()
                    .that().haveNameNotMatching(".*\\.PeerCluster[\\w$]*")
                    .and().resideInAPackage("..modules.p2pdiscovery..")
                    .should().dependOnClassesThat(resideInAPackage("io.scalecube.."))
                    .as("scalecube is confined to PeerCluster so the engine stays replaceable");
}
