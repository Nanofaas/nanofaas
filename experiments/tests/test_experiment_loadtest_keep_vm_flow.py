from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "wizard" / "experiment.py"


def test_wizard_keeps_vm_until_loadtests_complete():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "keep_vm_during_deploy = config.deploy.keep_vm or config.run_loadtest" in script
    assert "try:" in script
    assert "run_deploy(" in script
    assert "run_loadtest=config.run_loadtest" in script
    assert "loadtest=config.loadtest" in script
    assert "if not config.deploy.keep_vm:" in script
    assert "cleanup_vm(config.deploy.vm_name)" in script


def test_wizard_disables_control_plane_only_when_loadtest_enabled():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "control_plane_only = not run_loadtest" in script
    assert "control_plane_only=control_plane_only" in script
    assert "host_rebuild_images=config.host_rebuild_images" in script
    assert "loadtest_workloads=loadtest_workloads" in script
    assert "loadtest_runtimes=loadtest_runtimes" in script
    assert "Strategia build immagini: {image_build_label}" in script


def test_wizard_supports_reusing_existing_host_images():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "host_rebuild_images: bool" in script
    assert "docker image inspect" in script
    assert "build_host_control_plane_image_ref" in script
    assert "hashlib.sha256" in script
    assert "host-{tag}-" not in script
    assert 'return f"nanofaas/control-plane:host-{build_mode}-{fingerprint}"' in script
    assert ".image-cache/control-plane" in script
    assert "manifest.json" in script
    assert "control_plane_compat_manifest_path" in script
    assert "control_plane_cache_manifest_is_valid" in script
    assert "docker image ls " in script
    assert "--format '{{{{.Repository}}}}:{{{{.Tag}}}}'" in script
    assert "Immagini riusabili da tag differente" in script
    assert "Immagini host gia presenti localmente" in script
    assert "Vuoi comunque ricompilarle?" in script
