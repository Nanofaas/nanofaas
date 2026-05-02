"""
deploy_host_runner.py

DeployHostE2eRunner: executes the deploy-host E2E workflow.

Mirrors the logic of the deleted e2e-deploy-host-backend.sh (M9).
"""
from __future__ import annotations

from controlplane_tool.console import console, phase, success, warning, skip, fail, status, workflow_log, workflow_step

import os
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from controlplane_tool.scenario_helpers import resolve_scenario as _resolve_scenario_file
from controlplane_tool.scenario_runtime import (
    FakeControlPlane,
    select_container_runtime,
    wait_for_http_any_status,
)
from controlplane_tool.registry_runtime import ensure_local_registry
from controlplane_tool.shell_backend import ShellExecutionResult, SubprocessShell
from controlplane_tool.workflow_progress import WorkflowProgressReporter

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


class DeployHostE2eRunner:
    """Executes the deploy-host E2E workflow.

    Mirrors the logic of the deleted e2e-deploy-host-backend.sh.
    Builds the CLI on the host, starts a local registry and fake CP,
    then runs `nanofaas deploy` for each selected function.
    """

    REGISTRY_PORT = 5050
    CONTROL_PLANE_PORT = 18080

    def __init__(
        self,
        repo_root: Path,
        *,
        registry_port: int | None = None,
        control_plane_port: int | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.registry_port = registry_port or self.REGISTRY_PORT
        self.control_plane_port = control_plane_port or self.CONTROL_PLANE_PORT
        self._cli_bin = (
            self.repo_root
            / "nanofaas-cli"
            / "build"
            / "install"
            / "nanofaas-cli"
            / "bin"
            / "nanofaas"
        )
        self._shell = SubprocessShell()

    def _run(self, command: list[str], *, check: bool = True) -> ShellExecutionResult:
        result = self._shell.run(
            command,
            cwd=self.repo_root,
            env=os.environ.copy(),
            dry_run=False,
        )
        if check and result.return_code != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "command failed"
            raise RuntimeError(f"{' '.join(command)}: {detail}")
        return result

    def _resolve_functions(self, resolved: "ResolvedScenario | None") -> list[tuple[str, Path | None]]:
        """Return [(function_key, example_dir_or_None)] for selected functions."""
        if resolved is None:
            return [("deploy-e2e", None)]
        return [(fn.key, fn.example_dir) for fn in resolved.functions]

    def _build_cli(self, *, skip: bool = False) -> None:
        if skip:
            if not self._cli_bin.exists():
                raise RuntimeError(f"CLI binary not found at {self._cli_bin}")
            return
        workflow_log("Building nanofaas CLI on host...")
        self._run(["./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"])
        if not self._cli_bin.exists():
            raise RuntimeError(f"CLI binary not found at {self._cli_bin}")

    def _start_registry(self, container_name: str, docker: str = "docker") -> None:
        workflow_log(f"Starting local registry ({container_name}) on port {self.registry_port}")
        _ = docker
        result = ensure_local_registry(
            registry=f"localhost:{self.registry_port}",
            container_name=container_name,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "registry failed")

    def _stop_registry(self, container_name: str, docker: str = "docker") -> None:
        self._run([docker, "rm", "-f", container_name], check=False)

    def _write_function_yaml(
        self,
        work_dir: Path,
        function_name: str,
        image_repo: str,
        tag: str,
        example_dir: Path | None,
    ) -> Path:
        if example_dir:
            context_dir = str(example_dir)
            dockerfile = str(example_dir / "Dockerfile")
        else:
            ctx = work_dir / "context"
            ctx.mkdir(exist_ok=True)
            (ctx / "Dockerfile").write_text(
                'FROM scratch\nLABEL org.opencontainers.image.title="nanofaas-deploy-host-e2e"\n',
                encoding="utf-8",
            )
            context_dir = str(ctx)
            dockerfile = str(ctx / "Dockerfile")

        spec = (
            f"name: {function_name}\n"
            f"image: localhost:{self.registry_port}/{image_repo}:{tag}\n"
            "executionMode: DEPLOYMENT\n"
            "x-cli:\n"
            "  build:\n"
            f"    context: {context_dir}\n"
            f"    dockerfile: {dockerfile}\n"
            "    push: true\n"
        )
        fn_yaml = work_dir / "function.yaml"
        fn_yaml.write_text(spec, encoding="utf-8")
        return fn_yaml

    def _verify_registry_push(self, image_repo: str, tag: str) -> None:
        import httpx

        url = f"http://127.0.0.1:{self.registry_port}/v2/{image_repo}/tags/list"
        tags_json = httpx.get(url, timeout=10).text
        if f'"{tag}"' not in tags_json:
            raise RuntimeError(f"Tag {tag} not found in registry response: {tags_json}")

    def _verify_register_request(self, request_body_path: Path, function_name: str, image_repo: str, tag: str) -> None:
        if not request_body_path.exists() or request_body_path.stat().st_size == 0:
            raise RuntimeError(f"Missing register request body at {request_body_path}")
        body = request_body_path.read_text(encoding="utf-8")
        if f'"name"' not in body or function_name not in body:
            raise RuntimeError(f"Function name not found in request body: {body}")
        expected_image = f"localhost:{self.registry_port}/{image_repo}:{tag}"
        if expected_image not in body:
            raise RuntimeError(f"Image not found in request body. Expected: {expected_image}")

    def run(
        self,
        scenario_file: Path | None = None,
        *,
        resolved_scenario: "ResolvedScenario | None" = None,
        skip_cli_build: bool = False,
    ) -> None:
        import time

        resolved = (
            resolved_scenario
            if resolved_scenario is not None
            else _resolve_scenario_file(scenario_file)
        )
        selected_functions = self._resolve_functions(resolved)

        docker = select_container_runtime() or "docker"
        tag = f"e2e-{int(time.time())}"
        registry_container = f"nanofaas-deploy-e2e-registry-{int(time.time())}"

        phase("Build")
        with workflow_step(task_id="deploy-host.build", title="Build"):
            self._build_cli(skip=skip_cli_build)

        work_dir = Path(tempfile.mkdtemp(prefix="nanofaas-deploy-host-e2e."))
        request_body_path = work_dir / "function-request.json"
        fake_cp = FakeControlPlane(self.control_plane_port, request_body_path)

        try:
            phase("Deploy")
            with workflow_step(task_id="deploy-host.deploy", title="Deploy"):
                self._start_registry(registry_container, docker)
                fake_cp.start(work_dir)
                if not wait_for_http_any_status(f"http://127.0.0.1:{self.control_plane_port}/", max_attempts=30):
                    raise RuntimeError(f"Fake control-plane did not start on port {self.control_plane_port}")

            cp_url = f"http://127.0.0.1:{self.control_plane_port}"

            phase("Verify")
            with workflow_step(task_id="deploy-host.verify", title="Verify"):
                reporter = WorkflowProgressReporter.current()
                for function_key, example_dir in selected_functions:
                    with reporter.child(
                        f"deploy-host.verify.{function_key}",
                        f"Deploying {function_key}",
                    ):
                        image_repo = f"nanofaas/{function_key}"
                        fn_yaml = self._write_function_yaml(work_dir, function_key, image_repo, tag, example_dir)
                        request_body_path.unlink(missing_ok=True)
                        self._run(
                            [str(self._cli_bin), "--endpoint", cp_url, "deploy", "-f", str(fn_yaml)],
                            check=True,
                        )
                        self._verify_registry_push(image_repo, tag)
                        self._verify_register_request(request_body_path, function_key, image_repo, tag)

            success("Deploy host E2E:")
        finally:
            fake_cp.stop()
            self._stop_registry(registry_container, docker)
            import shutil
            shutil.rmtree(work_dir, ignore_errors=True)
