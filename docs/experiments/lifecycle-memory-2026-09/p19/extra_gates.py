"""Remaining proportional section-8 selections and packaging, run serially."""
import pathlib
import shutil
import sys
from verification import ROOT, catalog, execute

destination = pathlib.Path(sys.argv[1])
flags = ['--offline', '--no-parallel', '--console=plain', '--rerun-tasks', '-I', str(pathlib.Path(__file__).with_name('verification.init.gradle').resolve())]
commands = catalog()
for label, group, selection in [
    ('sync-alone', 'G6', 'sync-queue'),
    ('offload-async', 'G9', 'async-queue,offload'),
    ('offload-sync', 'G9', 'sync-queue,offload'),
    ('scaler-combined', 'G12', 'async-queue,autoscaler,concurrency-control'),
    ('governor-combined', 'G13', 'async-queue,autoscaler,concurrency-control')]:
    if (destination / label).exists():
        continue
    command = [('-PcontrolPlaneModules=' + selection) if s.startswith('-PcontrolPlaneModules=') else s for s in commands[group]]
    result = execute(label, command + flags[3:], destination)
    if result['exit_code']:
        raise SystemExit(1)

prefix = 'it.unimib.datai.nanofaas.controlplane.'
for label, selection, tests in [
    ('configured-T1', 'none', ['P07ConfiguredHttpCalibrationTest.t1CoreOnlySyncUnkeyedThroughConfiguredHttpRuntime']),
    ('configured-T2', 'async-queue', ['P07ConfiguredHttpCalibrationTest.t2AsyncKeyedShapesThroughConfiguredQueueHttpRuntime', 'InvocationQuotaHttpTest']),
    ('contract-default', None, ['api.OpenApiRouteCoverageTest', 'IssueCoverageTest', 'ControlPlaneAutoConfigurationImportsTest', 'architecture.CoreArchitectureTest']),
    ('contract-all', 'all', ['api.OpenApiRouteCoverageTest', 'IssueCoverageTest', 'ControlPlaneAutoConfigurationImportsTest', 'architecture.CoreArchitectureTest']),
    ('catalog-cost', 'none', ['registry.FunctionCatalogCostMeasurementTest'])]:
    if (destination / label).exists():
        continue
    command = ['./gradlew', ':control-plane:test'] + (['-PcontrolPlaneModules=' + selection] if selection else [])
    for test in tests:
        command += ['--tests', prefix + test]
    result = execute(label, command + flags, destination)
    if result['exit_code']:
        raise SystemExit(1)

for label, command in [
    ('offload-http', ['./gradlew', ':control-plane-modules:offload:test', '-PcontrolPlaneModules=sync-queue,offload',
                      '--tests', 'it.unimib.datai.nanofaas.modules.offload.OffloadPressureE2eTest',
                      '--tests', 'it.unimib.datai.nanofaas.modules.offload.OffloadHeaderLossE2eTest',
                      '--tests', 'it.unimib.datai.nanofaas.modules.offload.OffloadHopGuardE2eTest']),
    ('metadata', ['./gradlew', ':control-plane-modules:build-metadata:test', '-PcontrolPlaneModules=all']),
    ('jvm-sdk-full', ['./gradlew', ':sdks:java:test', ':sdks:java-lite:test', ':services:java:warm-echo:test']),
    ('selector-tests', ['./gradlew', '-p', 'platform/gradle-plugin', 'test', '--tests', 'it.unimib.datai.nanofaas.gradle.ControlPlaneModulesPluginTest',
                        '--tests', 'it.unimib.datai.nanofaas.gradle.ModuleConstraintResolverTest', '--tests', 'it.unimib.datai.nanofaas.gradle.RepositoryModuleDescriptorsTest'])]:
    if (destination / label).exists():
        continue
    result = execute(label, command + ([f for f in flags if f != '--offline'] if label == 'selector-tests' else flags), destination)
    if result['exit_code']:
        raise SystemExit(1)

for selection in ['async-queue,sync-queue', 'k8s-deployment-provider,container-deployment-provider', 'none,offload', 'unknown']:
    if (destination / ('negative-' + selection)).exists():
        continue
    result = execute('negative-' + selection, ['./gradlew', 'help', '-PcontrolPlaneModules=' + selection, '--offline', '--console=plain'], destination, expected=1)
    if result['exit_code'] != 1:
        raise SystemExit(1)

artifacts = destination.parent / 'artifacts'
for selection in ['none', 'async-queue', 'sync-queue', 'sync-queue,runtime-config', 'container-deployment-provider', 'all', 'default']:
    if (destination / ('package-' + selection)).exists():
        continue
    command = ['./gradlew', ':control-plane:bootJar'] + (['-PcontrolPlaneModules=' + selection] if selection != 'default' else [])
    result = execute('package-' + selection, command + flags, destination)
    if result['exit_code']:
        raise SystemExit(1)
    shutil.copyfile(ROOT / 'platform/control-plane/build/libs/app.jar', artifacts / ('B-' + selection + '.jar'))
