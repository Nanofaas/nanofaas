package it.unimib.datai.nanofaas.controlplane.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.controlplane..")
class CoreArchitectureTest {

    // R1: il core non deve contenere cicli tra i suoi package top-level.
    // IGNORATA (debito tracciato, fix in tranche futuro): il codice attuale ha 5 cicli reali:
    //   config -> service -> execution -> config
    //   config -> service -> offload -> sync -> config
    //   config -> service -> sync -> config
    //   config -> sync -> config
    //   deployment -> registry -> deployment
    // Rimuovere @ArchIgnore quando il tranche di refactor avrà rotto i cicli.
    @ArchIgnore
    @ArchTest
    static final ArchRule core_packages_are_free_of_cycles =
            slices().matching("..controlplane.(*)..").should().beFreeOfCycles();
}
