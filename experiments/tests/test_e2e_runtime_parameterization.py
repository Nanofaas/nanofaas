import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def run_script(script_path: Path, *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    return subprocess.run(
        ["bash", str(script_path)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=merged_env,
    )


def test_runtime_config_script_skips_for_rust_control_plane_runtime():
    script = REPO_ROOT / "experiments" / "e2e-runtime-config.sh"
    proc = run_script(script, env={"CONTROL_PLANE_RUNTIME": "rust"})
    combined = proc.stdout + proc.stderr
    assert proc.returncode == 0
    assert "SKIP:" in combined
    assert "CONTROL_PLANE_RUNTIME=rust" in combined


def test_memory_ab_script_skips_for_rust_control_plane_runtime():
    script = REPO_ROOT / "experiments" / "e2e-memory-ab.sh"
    proc = run_script(script, env={"CONTROL_PLANE_RUNTIME": "rust"})
    combined = proc.stdout + proc.stderr
    assert proc.returncode == 0
    assert "SKIP:" in combined
    assert "CONTROL_PLANE_RUNTIME=rust" in combined


def test_loadtest_registry_script_skips_for_rust_control_plane_runtime():
    script = REPO_ROOT / "experiments" / "e2e-loadtest-registry.sh"
    proc = run_script(script, env={"CONTROL_PLANE_RUNTIME": "rust"})
    combined = proc.stdout + proc.stderr
    assert proc.returncode == 0
    assert "SKIP:" in combined
    assert "CONTROL_PLANE_RUNTIME=rust" in combined
