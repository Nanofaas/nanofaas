"""
gradle_ops.py

Build, image, and test operations that delegate to Gradle or cargo.

Extracted from adapters.py (ShellCommandAdapter) to satisfy single-responsibility.
"""
from __future__ import annotations

import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.building.requests import BuildRequest
from controlplane_tool.building.gradle_planner import build_gradle_command
from controlplane_tool.infra.runtimes.mockk8s import default_mockk8s_test_selectors
from controlplane_tool.core.models import BuildAction, Profile
from controlplane_tool.core.shell_backend import SubprocessShell


@dataclass(frozen=True)
class CommandResult:
    """Return type for logged subprocess operations."""
    ok: bool
    detail: str


def run_logged(
    command: list[str],
    run_dir: Path,
    log_name: str,
    *,
    repo_root: Path,
) -> CommandResult:
    """Run *command* inside *repo_root*, appending stdout/stderr to *run_dir/log_name*."""
    log_path = run_dir / log_name
    start = time.time()
    shell = SubprocessShell()
    with log_path.open("a", encoding="utf-8") as log_file:
        log_file.write(f"$ {' '.join(command)}\n")
        completed = shell.run(
            command,
            cwd=repo_root,
            dry_run=False,
        )
        if completed.stdout:
            log_file.write(completed.stdout)
        if completed.stderr:
            log_file.write(completed.stderr)
    duration_ms = int((time.time() - start) * 1000)
    if completed.return_code == 0:
        return CommandResult(ok=True, detail=f"ok ({duration_ms} ms)")
    return CommandResult(
        ok=False,
        detail=f"exit={completed.return_code} ({duration_ms} ms), see {log_path.name}",
    )


class GradleOps:
    """Stateless building / test operations that invoke Gradle or cargo."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> CommandResult:
        return run_logged(command, run_dir, log_name, repo_root=self.repo_root)

    def _modules_selector(self, profile: Profile) -> str:
        if not profile.modules:
            return "none"
        return ",".join(profile.modules)

    def _build_gradle_command(
        self,
        action: BuildAction,
        profile: Profile,
        extra_gradle_args: list[str] | None = None,
    ) -> list[str]:
        request = BuildRequest(
            action=action,
            profile="core",
            modules=self._modules_selector(profile),
        )
        return build_gradle_command(
            repo_root=self.repo_root,
            request=request,
            extra_gradle_args=extra_gradle_args,
        )

    def preflight_missing(self, profile: Profile) -> list[str]:
        """Return a list of missing tool names; empty list means all present."""
        missing: list[str] = []
        if shutil.which("docker") is None:
            missing.append("docker")
        requires_gradle = profile.control_plane.implementation == "java" or (
            profile.tests.enabled
            and (profile.tests.api or profile.tests.e2e_mockk8s or profile.tests.metrics)
        )
        if requires_gradle and not (self.repo_root / "gradlew").exists():
            missing.append("gradlew")
        if profile.control_plane.implementation == "rust" and shutil.which("cargo") is None:
            missing.append("cargo")
        if profile.tests.enabled and profile.tests.metrics and shutil.which("k6") is None:
            missing.append("k6")
        return missing

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            manifest = rust_dir / "Cargo.toml"
            if not manifest.exists():
                return (False, f"Rust control plane manifest not found at {manifest}")
            result = self._run(
                ["cargo", "build", "--release", "--manifest-path", str(manifest)],
                run_dir,
                "building.log",
            )
            return (result.ok, result.detail)

        if profile.control_plane.build_mode == "native":
            command = self._build_gradle_command("native", profile)
        else:
            command = self._build_gradle_command("building", profile)
        result = self._run(command, run_dir, "building.log")
        return (result.ok, result.detail)

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        tag = f"nanofaas/control-plane:{profile.name}"
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            dockerfile = rust_dir / "Dockerfile"
            if not dockerfile.exists():
                return (False, f"Rust Dockerfile not found at {dockerfile}")
            result = self._run(
                ["docker", "build", "-f", str(dockerfile), "-t", tag, str(rust_dir)],
                run_dir,
                "building.log",
            )
            return (result.ok, f"{result.detail}; image={tag}")

        if profile.control_plane.build_mode == "native":
            command = self._build_gradle_command(
                "image",
                profile,
                extra_gradle_args=[f"-PcontrolPlaneImage={tag}"],
            )
        else:
            command = self._build_gradle_command("building", profile)
            first = self._run(command, run_dir, "building.log")
            if not first.ok:
                return (False, first.detail)
            command = [
                "docker",
                "build",
                "-f",
                str(self.repo_root / "control-plane" / "Dockerfile"),
                "-t",
                tag,
                str(self.repo_root / "control-plane"),
            ]
        result = self._run(command, run_dir, "building.log")
        return (result.ok, f"{result.detail}; image={tag}")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = self._build_gradle_command(
            "test", profile, extra_gradle_args=["--tests", "*ControlPlaneApiTest"]
        )
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        extra_gradle_args: list[str] = []
        for selector in default_mockk8s_test_selectors():
            extra_gradle_args.extend(["--tests", selector])
        command = self._build_gradle_command("test", profile, extra_gradle_args=extra_gradle_args)
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)
