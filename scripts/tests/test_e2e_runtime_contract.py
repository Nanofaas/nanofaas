from __future__ import annotations

import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"


def run_shell(script: str, *, env: dict[str, str] | None = None) -> str:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)

    proc = subprocess.run(
        ["bash", "-lc", script],
        text=True,
        capture_output=True,
        check=True,
        env=merged_env,
    )
    return proc.stdout.strip()


def test_runtime_kind_and_rust_detection_behave_as_expected():
    source = f"source '{SCRIPT}'"

    out = run_shell(f"{source}; e2e_runtime_kind")
    assert out == "java"

    out = run_shell(
        f"{source}; e2e_runtime_kind",
        env={"CONTROL_PLANE_RUNTIME": "RUST"},
    )
    assert out == "rust"

    out = run_shell(
        f"{source}; if e2e_is_rust_runtime; then echo rust; else echo java; fi",
        env={"CONTROL_PLANE_RUNTIME": "rust"},
    )
    assert out == "rust"

    out = run_shell(
        f"{source}; if e2e_is_rust_runtime; then echo rust; else echo java; fi",
        env={"CONTROL_PLANE_RUNTIME": "invalid"},
    )
    assert out == "java"


def test_build_dispatch_helpers_route_to_runtime_specific_paths():
    source = f"source '{SCRIPT}'"
    cmd = (
        f"{source}; "
        "e2e_require_vm_exec(){ return 0; }; "
        "vm_exec(){ echo VMEXEC:$*; }; "
        "e2e_build_core_jars(){ echo CORE_JARS:$1; }; "
        "e2e_build_function_runtime_jar(){ echo FR_JAR:$1; }; "
        "e2e_build_rust_control_plane_image(){ echo RUST_IMG:$1:$2; }; "
        "CONTROL_PLANE_RUNTIME=java; "
        "e2e_build_control_plane_artifacts /tmp/a; "
        "e2e_build_control_plane_image /tmp/a cp-java:test; "
        "CONTROL_PLANE_RUNTIME=rust; "
        "e2e_build_control_plane_artifacts /tmp/b; "
        "e2e_build_control_plane_image /tmp/b cp-rust:test"
    )
    out = run_shell(cmd)
    lines = out.splitlines()

    assert "CORE_JARS:/tmp/a" in lines
    assert any("control-plane/Dockerfile control-plane/" in line for line in lines)
    assert "FR_JAR:/tmp/b" in lines
    assert "RUST_IMG:/tmp/b:cp-rust:test" in lines


def test_sync_env_renderer_emits_both_aliases_and_can_be_empty():
    source = f"source '{SCRIPT}'"

    enabled = run_shell(f"{source}; e2e_render_control_plane_sync_env true")
    assert "- name: SYNC_QUEUE_ENABLED" in enabled
    assert "- name: NANOFAAS_SYNC_QUEUE_ENABLED" in enabled
    assert 'value: "true"' in enabled

    empty = run_shell(
        f"{source}; "
        "if [ -n \"$(e2e_render_control_plane_sync_env '')\" ]; then echo non-empty; else echo empty; fi"
    )
    assert empty == "empty"
