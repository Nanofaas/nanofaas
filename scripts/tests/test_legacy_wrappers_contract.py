from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"

# Internal shell backends: these are migration targets that must be deleted by M13.
# Each entry is tracked here so their removal is tested progressively as M9–M11 land.
INTERNAL_BACKENDS_PENDING_MIGRATION = (
    # M9 complete: e2e-container-local-backend.sh, e2e-deploy-host-backend.sh, scenario-manifest.sh deleted
    # M10 complete: e2e-cli-backend.sh, e2e-cli-host-backend.sh deleted
    # M11 complete: e2e-k3s-curl-backend.sh, e2e-helm-stack-backend.sh, e2e-k3s-common.sh deleted
)

SHIM_TARGETS = {
    "control-plane-build.sh": 'exec "$(dirname "$0")/controlplane.sh" "$@"',
    "controlplane-tool.sh": 'exec "$(dirname "$0")/controlplane.sh" tui "$@"',
    "e2e.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run docker "$@"',
    "e2e-all.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e all "$@"',
    "e2e-buildpack.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run buildpack "$@"',
    "e2e-container-local.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run container-local "$@"',
    "e2e-k3s-junit-curl.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run k3s-junit-curl "$@"',
    "e2e-k3s-helm.sh": 'exec "$(dirname "$0")/controlplane.sh" e2e run helm-stack "$@"',
    "e2e-cli.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run vm "$@"',
    "e2e-cli-host-platform.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run host-platform "$@"',
    "e2e-cli-deploy-host.sh": 'exec "$(dirname "$0")/controlplane.sh" cli-test run deploy-host "$@"',
}


def test_legacy_wrappers_are_documented_as_compatibility_only() -> None:
    for name, expected_exec in SHIM_TARGETS.items():
        script = (SCRIPTS_DIR / name).read_text(encoding="utf-8")
        assert "wrapper" in script.lower(), name
        assert "scripts/controlplane.sh" in script or "controlplane.sh" in script, name
        assert expected_exec in script, name
        assert "gradlew" not in script, name
        assert len(script.strip().splitlines()) <= 7, name


def test_loadtest_wrapper_routes_to_python_loadtest_run() -> None:
    # M12: e2e-loadtest.sh now routes to controlplane.sh loadtest run (not experiments script)
    script = (SCRIPTS_DIR / "e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script
    assert "loadtest run" in script
    assert "experiments/e2e-loadtest.sh" not in script


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


def test_scripts_lib_directory_is_empty_after_full_migration() -> None:
    """M13: All internal shell backends have been deleted — scripts/lib/ should be empty."""
    lib_dir = SCRIPTS_DIR / "lib"
    if not lib_dir.exists():
        return  # already fully cleaned
    remaining = [f for f in lib_dir.iterdir() if f.name != "__pycache__"]
    assert remaining == [], (
        f"scripts/lib/ still has files after full migration: "
        + ", ".join(str(f.name) for f in remaining)
    )


def test_legacy_cli_wrapper_scripts_are_deleted() -> None:
    """e2e-cli*.sh must be deleted — cli-stack is the canonical VM-backed CLI path."""
    for name in ("e2e-cli.sh", "e2e-cli-host-platform.sh", "e2e-cli-deploy-host.sh"):
        assert not (SCRIPTS_DIR / name).exists(), (
            f"{name!r} still exists — delete it as part of legacy CLI consumer cleanup"
        )


def test_python_cli_exposes_all_expected_command_groups() -> None:
    """M13: All scenario command groups must be registered in the controlplane-tool CLI."""
    import sys
    from pathlib import Path as _Path

    tool_src = _Path(__file__).resolve().parents[2] / "tools" / "controlplane" / "src"
    if str(tool_src) not in sys.path:
        sys.path.insert(0, str(tool_src))

    from controlplane_tool.main import app  # noqa: PLC0415
    from typer.testing import CliRunner  # noqa: PLC0415

    result = CliRunner().invoke(app, ["--help"])
    assert result.exit_code == 0
    for group in ("e2e", "cli-test", "loadtest", "vm", "functions", "tui"):
        assert group in result.stdout, f"Expected command group {group!r} not found in CLI help"
