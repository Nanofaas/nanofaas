from __future__ import annotations

from pathlib import Path


def k3s_curl_verify_command() -> tuple[str, ...]:
    return ("python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack")


def loadtest_run_command() -> tuple[str, ...]:
    return ("uv", "run", "--project", "tools/controlplane", "--locked",
            "controlplane-tool", "loadtest", "run")


def autoscaling_command(repo_root: Path) -> tuple[str, ...]:
    return ("uv", "run", "--project", str(Path(repo_root) / "tools" / "controlplane"),
            "--locked", "python", str(Path(repo_root) / "experiments" / "autoscaling.py"))
