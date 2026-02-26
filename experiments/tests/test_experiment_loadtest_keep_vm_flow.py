from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "wizard" / "experiment.py"


def test_wizard_keeps_vm_until_loadtests_complete():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "keep_vm_during_deploy = config.deploy.keep_vm or config.run_loadtest" in script
    assert "force_keep_vm = env_bool(\"E2E_WIZARD_FORCE_KEEP_VM\", False)" in script
    assert "KEEP_VM forzato a true da orchestratore" in script
    assert "try:" in script
    assert "run_deploy(" in script
    assert "run_loadtest=config.run_loadtest" in script
    assert "loadtest=config.loadtest" in script
    assert "write_wizard_context(config)" in script
    assert "defer_loadtest_execution = env_bool(\"E2E_WIZARD_DEFER_LOADTEST_EXECUTION\", False)" in script
    assert "Esecuzione load test demandata a orchestratore esterno (helm-stack phase 2)." in script
    assert "should_cleanup_vm = not config.deploy.keep_vm and not (defer_loadtest_execution and config.run_loadtest)" in script
    assert "if should_cleanup_vm:" in script
    assert "cleanup_vm(config.deploy.vm_name)" in script


def test_wizard_disables_control_plane_only_when_loadtest_enabled():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "forced_run_loadtest = os.environ.get(\"E2E_WIZARD_FORCE_RUN_LOADTEST\")" in script
    assert "capture_only = env_bool(\"E2E_WIZARD_CAPTURE_LOADTEST_CONFIG\", False)" in script
    assert "Load test disabilitato da orchestratore (helm-stack phase 1)." in script
    assert "Load test fase 1 disabilitato da orchestratore: raccolta configurazione abilitata." in script
    assert "control_plane_only = not run_loadtest" in script
    assert "control_plane_only=control_plane_only" in script
    assert "control_plane_runtime=config.control_plane_runtime" in script
    assert "host_rebuild_images=config.host_rebuild_images" in script
    assert "loadtest_workloads=loadtest_workloads" in script
    assert "loadtest_runtimes=loadtest_runtimes" in script
    assert "RUN_LOADTEST={'true' if config.run_loadtest else 'false'}" in script
    assert "LOADTEST_WORKLOADS={','.join(config.loadtest.workloads)}" in script
    assert "INVOCATION_MODE={config.loadtest.invocation_mode}" in script
    assert "Strategia build immagini: {image_build_label}" in script


def test_wizard_supports_reusing_existing_host_images():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "host_rebuild_images: bool" in script
    assert "Runtime control-plane:" in script
    assert "if normalized_runtime == \"java\":" in script
    assert "control_plane_native_build = False" in script
    assert "docker image inspect" in script
    assert "build_host_control_plane_image_ref" in script
    assert "hashlib.sha256" in script
    assert "host-{tag}-" not in script
    assert 'return f"nanofaas/control-plane:host-java-v{PROJECT_VERSION}-{build_mode}-{fingerprint}"' in script
    assert 'return f"nanofaas/control-plane:host-{runtime}-v{PROJECT_VERSION}-{fingerprint}"' in script
    assert ".image-cache/control-plane" in script
    assert "manifest.json" in script
    assert "control_plane_compat_manifest_path" in script
    assert "control_plane_cache_manifest_is_valid" in script
    assert "docker image ls " in script
    assert "--format '{{{{.Repository}}}}:{{{{.Tag}}}}'" in script
    assert "Immagini riusabili da tag differente" in script
    assert "Immagini host gia presenti localmente" in script
    assert "Decisione per immagini in cache:" in script
    assert "Usa cache" in script
    assert "Ricrea immagine" in script
    assert "Build Java per" in script
    assert "Immagini host mancanti: verranno compilate automaticamente." in script
