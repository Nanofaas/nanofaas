from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"

# Internal shell backends: these are migration targets that must be deleted by M13.
# Each entry is tracked here so their removal is tested progressively as M9–M11 land.
INTERNAL_BACKENDS_PENDING_MIGRATION = (
    # M9 complete: e2e-container-local-backend.sh, e2e-deploy-host-backend.sh, scenario-manifest.sh deleted
    "lib/e2e-cli-backend.sh",                  # M10: replace with cli_runtime.py
    "lib/e2e-cli-host-backend.sh",             # M10: replace with cli_runtime.py
    "lib/e2e-k3s-curl-backend.sh",             # M11: replace with k3s_runtime.py
    "lib/e2e-helm-stack-backend.sh",           # M11: replace with k3s_runtime.py
    "lib/e2e-k3s-common.sh",                   # M11: absorb into Python adapters
)

SHIM_TARGETS = {
    "control-plane-build.sh": 'exec "$(dirname "$0")/controlplane.sh" "$@"',
    "controlplane-tool.sh": 'exec "$(dirname "$0")/controlplane.sh" tui "$@"',
    "e2e.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run docker "$@"',
    "e2e-all.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e all "$@"',
    "e2e-buildpack.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run buildpack "$@"',
    "e2e-container-local.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run container-local "$@"',
    "e2e-k3s-curl.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run k3s-curl "$@"',
    "e2e-k3s-helm.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run helm-stack "$@"',
    "e2e-k8s-vm.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run k8s-vm "$@"',
    "e2e-cli.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run vm "$@"',
    "e2e-cli-host-platform.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run host-platform "$@"',
    "e2e-cli-deploy-host.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run deploy-host "$@"',
}


def test_legacy_wrappers_are_documented_as_compatibility_only() -> None:
    for name, expected_exec in SHIM_TARGETS.items():
        script = (SCRIPTS_DIR / name).read_text(encoding="utf-8")
        assert "Compatibility wrapper" in script, name
        assert "scripts/controlplane.sh" in script or "controlplane.sh" in script, name
        assert expected_exec in script, name
        assert "gradlew" not in script, name
        assert len(script.strip().splitlines()) <= 7, name


def test_loadtest_wrapper_remains_explicit_legacy_backend() -> None:
    script = (SCRIPTS_DIR / "e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "scripts/controlplane.sh loadtest run" in script
    assert "experiments/e2e-loadtest.sh" in script


def test_internal_backends_exist_pending_migration() -> None:
    """Verifies each internal backend is still present and awaiting Python migration.

    This test acts as a migration registry.  Remove each entry here AFTER the
    corresponding milestone (M9–M11) deletes the file and the Python path is green.
    """
    for backend in INTERNAL_BACKENDS_PENDING_MIGRATION:
        path = SCRIPTS_DIR / backend
        assert path.exists(), (
            f"Backend {backend!r} was expected to exist as a pending-migration target "
            f"but was already deleted.  Remove it from INTERNAL_BACKENDS_PENDING_MIGRATION."
        )
