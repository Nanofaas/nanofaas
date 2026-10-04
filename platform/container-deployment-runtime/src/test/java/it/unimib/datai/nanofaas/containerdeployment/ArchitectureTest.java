package it.unimib.datai.nanofaas.containerdeployment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.containerdeployment",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule runtime_depends_only_on_jdk_logging_json_common_and_deployment_contracts = classes()
            .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.sun.net.httpserver..",
                    "org.slf4j..", "tools.jackson..", "it.unimib.datai.nanofaas.common..",
                    "it.unimib.datai.nanofaas.containerdeployment..")
                    .or(equivalentTo(it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider.class))
                    .or(equivalentTo(it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException.class))
                    .or(equivalentTo(it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult.class))
                    .or(equivalentTo(it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus.class)));
}
