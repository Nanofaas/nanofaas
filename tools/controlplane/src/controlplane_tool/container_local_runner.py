"""
container_local_runner.py

ContainerLocalE2eRunner: executes the container-local managed DEPLOYMENT E2E workflow.

Mirrors the logic of the deleted e2e-container-local-backend.sh (M9).
"""
from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from tenacity import RetryError, Retrying, stop_after_attempt, wait_fixed

from controlplane_tool.control_plane_api import ControlPlaneApi
from controlplane_tool.runtime_primitives import (
    CommandRunner,
    ContainerRuntimeOps,
    read_json_field,
    wrap_payload,
    write_json_file,
)
from controlplane_tool.scenario_helpers import resolve_scenario as _resolve_scenario_file
from controlplane_tool.scenario_runtime import (
    select_container_runtime,
    wait_for_http_ok,
)

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


class ContainerLocalE2eRunner:
    """Executes the container-local managed DEPLOYMENT E2E workflow.

    Mirrors the logic of the deleted e2e-container-local-backend.sh.
    """

    API_PORT = 18080
    MGMT_PORT = 18081

    def __init__(
        self,
        repo_root: Path,
        *,
        api_port: int | None = None,
        mgmt_port: int | None = None,
        runtime_adapter: str | None = None,
        control_plane_modules: str = "container-deployment-provider",
    ) -> None:
        self.repo_root = Path(repo_root)
        self.api_port = api_port or self.API_PORT
        self.mgmt_port = mgmt_port or self.MGMT_PORT
        self.runtime_adapter = runtime_adapter
        self.control_plane_modules = control_plane_modules
        self._runner = CommandRunner(repo_root=self.repo_root)
        self._api = ControlPlaneApi(
            base_url=f"http://127.0.0.1:{self.api_port}",
            mgmt_port=self.mgmt_port,
        )

    def _run(self, command: list[str], *, check: bool = True, **kwargs: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            cwd=str(self.repo_root),
            text=True,
            capture_output=True,
            check=check,
            env=os.environ.copy(),
            **kwargs,
        )

    def _resolve_function(
        self,
        resolved: "ResolvedScenario | None",
    ) -> tuple[str, str | None, str | None, str | None, Path | None]:
        """Return (name, image, runtime_kind, family, payload_path)."""
        if resolved is None or not resolved.functions:
            return "container-local-e2e", "nanofaas/function-runtime:e2e-container-local", None, None, None

        functions = resolved.functions
        if len(functions) != 1:
            raise RuntimeError(
                f"container-local supports exactly one selected function, got {len(functions)}"
            )
        fn = functions[0]
        if not fn.image:
            raise RuntimeError(f"selected function '{fn.key}' does not define an image")
        return fn.key, fn.image, fn.runtime, fn.family, fn.payload_path

    def _build_artifacts(self) -> None:
        print("[e2e-container-local] Building control-plane and function-runtime artifacts")
        self._run(
            [
                "./scripts/controlplane.sh",
                "jar",
                "--profile",
                "container-local",
                "--modules",
                self.control_plane_modules,
                "--",
                "--quiet",
            ]
        )
        self._run(["./gradlew", ":function-runtime:bootJar", "--quiet"])

    def _build_function_image(
        self,
        image: str,
        runtime_kind: str | None,
        family: str | None,
    ) -> None:
        print(f"[e2e-container-local] Building function image {image}")
        adapter = self.runtime_adapter or "docker"
        if runtime_kind is None:
            self._run([adapter, "build", "-t", image, "function-runtime"])
        elif runtime_kind == "java":
            self._run(
                [
                    "./gradlew",
                    f":examples:java:{family}:bootBuildImage",
                    f"-PfunctionImage={image}",
                    "--quiet",
                ]
            )
        elif runtime_kind == "java-lite":
            self._run([adapter, "build", "-t", image, "-f", f"examples/java/{family}-lite/Dockerfile", "."])
        elif runtime_kind == "go":
            self._run([adapter, "build", "-t", image, "-f", f"examples/go/{family}/Dockerfile", "."])
        elif runtime_kind == "python":
            self._run([adapter, "build", "-t", image, "-f", f"examples/python/{family}/Dockerfile", "."])
        elif runtime_kind == "exec":
            self._run([adapter, "build", "-t", image, "-f", f"examples/bash/{family}/Dockerfile", "."])
        else:
            raise RuntimeError(f"Unsupported function runtime: {runtime_kind}")

    def _managed_container_count(self, function_slug: str) -> int:
        adapter = self.runtime_adapter or "docker"
        result = subprocess.run(
            [adapter, "ps", "-a", "--filter", f"name=nanofaas-{function_slug}-r", "--format", "{{.Names}}"],
            text=True,
            capture_output=True,
            check=False,
        )
        names = [n for n in result.stdout.splitlines() if n.strip()]
        return len(names)

    def _wait_for_containers(self, function_slug: str, expected: int, max_attempts: int = 60) -> None:
        try:
            for attempt in Retrying(
                stop=stop_after_attempt(max_attempts),
                wait=wait_fixed(1),
            ):
                with attempt:
                    if self._managed_container_count(function_slug) != expected:
                        raise RuntimeError(f"waiting for {expected} containers")
        except RetryError as exc:
            raise RuntimeError(
                f"Timed out waiting for {expected} managed containers for '{function_slug}'"
            ) from exc

    def _cleanup(self, cp_proc: subprocess.Popen[str] | None, function_slug: str, function_name: str) -> None:
        adapter = self.runtime_adapter or "docker"
        try:
            import httpx
            httpx.delete(f"{self._api.base_url}/v1/functions/{function_name}", timeout=5)
        except Exception:
            pass
        if cp_proc is not None:
            cp_proc.terminate()
            try:
                cp_proc.wait(timeout=5)
            except Exception:
                cp_proc.kill()
        for suffix in ("r1", "r2", "r3"):
            subprocess.run(
                [adapter, "rm", "-f", f"nanofaas-{function_slug}-{suffix}"],
                capture_output=True,
                check=False,
            )

    def run(self, scenario_file: Path | None = None) -> None:
        import urllib.request

        resolved = _resolve_scenario_file(scenario_file)
        function_name, function_image, runtime_kind, family, payload_path = self._resolve_function(resolved)
        function_slug = "".join(c if c.isalnum() or c == "-" else "-" for c in function_name)

        adapter = select_container_runtime(self.runtime_adapter)
        if not adapter:
            raise RuntimeError("No Docker-compatible runtime found on PATH (docker, podman, nerdctl)")
        self.runtime_adapter = adapter

        self._build_artifacts()
        self._build_function_image(function_image, runtime_kind, family)

        control_plane_jar = self.repo_root / "control-plane" / "build" / "libs" / "app.jar"

        print("[e2e-container-local] Starting control-plane with container-local provider")
        log_file = tempfile.NamedTemporaryFile(suffix=".log", delete=False)
        cp_proc = subprocess.Popen(
            [
                "java",
                "-jar",
                str(control_plane_jar),
                f"--server.port={self.api_port}",
                f"--management.server.port={self.mgmt_port}",
                "--sync-queue.enabled=false",
                "--nanofaas.deployment.default-backend=container-local",
                f"--nanofaas.container-local.runtime-adapter={adapter}",
                "--nanofaas.container-local.bind-host=127.0.0.1",
            ],
            stdout=log_file,
            stderr=subprocess.STDOUT,
            cwd=str(self.repo_root),
        )
        log_file.close()

        try:
            print("[e2e-container-local] Waiting for control-plane readiness")
            if not wait_for_http_ok(self._api.health_url, max_attempts=90):
                raise RuntimeError("Control-plane did not become healthy")

            with tempfile.TemporaryDirectory() as tmp_dir:
                tmp = Path(tmp_dir)

                register_req = tmp / "register-request.json"
                write_json_file(
                    register_req,
                    self._api.register_body(
                        function_name,
                        function_image,
                        timeout_ms=5000,
                        concurrency=2,
                        queue_size=20,
                    ),
                )
                register_resp = tmp / "register.json"
                subprocess.run(
                    [
                        "curl",
                        "-fsS",
                        "-H",
                        "Content-Type: application/json",
                        "-d",
                        f"@{register_req}",
                        self._api.register_url,
                        "-o",
                        str(register_resp),
                    ],
                    check=True,
                )
                assert read_json_field(register_resp, "name") == function_name
                assert read_json_field(register_resp, "effectiveExecutionMode") == "DEPLOYMENT"
                assert read_json_field(register_resp, "deploymentBackend") == "container-local"
                endpoint_url = read_json_field(register_resp, "endpointUrl")
                if not endpoint_url:
                    raise RuntimeError("endpointUrl must be present for provider-backed registration")

                self._wait_for_containers(function_slug, 1)
                health_base = endpoint_url.rstrip("/invoke").rstrip("/")
                if not wait_for_http_ok(f"{health_base}/health"):
                    raise RuntimeError("Stable proxy endpoint did not become healthy")

                invoke_req = tmp / "invoke-request.json"
                if payload_path:
                    wrap_payload(payload_path, invoke_req)
                else:
                    invoke_req.write_text('{"input":{"message":"hello-container-local"}}', encoding="utf-8")

                invoke_resp = tmp / "invoke.json"
                subprocess.run(
                    [
                        "curl",
                        "-fsS",
                        "-H",
                        "Content-Type: application/json",
                        "-d",
                        f"@{invoke_req}",
                        self._api.invoke_url(function_name),
                        "-o",
                        str(invoke_resp),
                    ],
                    check=True,
                )
                assert read_json_field(invoke_resp, "status") == "success"

                print("[e2e-container-local] Scaling managed function to 2 replicas")
                scale_resp = tmp / "scale.json"
                subprocess.run(
                    [
                        "curl",
                        "-fsS",
                        "-X",
                        "PUT",
                        "-H",
                        "Content-Type: application/json",
                        "-d",
                        '{"replicas":2}',
                        self._api.replicas_url(function_name),
                        "-o",
                        str(scale_resp),
                    ],
                    check=True,
                )
                assert read_json_field(scale_resp, "replicas") == 2
                self._wait_for_containers(function_slug, 2)

                invoke_resp2 = tmp / "invoke-scaled.json"
                subprocess.run(
                    [
                        "curl",
                        "-fsS",
                        "-H",
                        "Content-Type: application/json",
                        "-d",
                        f"@{invoke_req}",
                        self._api.invoke_url(function_name),
                        "-o",
                        str(invoke_resp2),
                    ],
                    check=True,
                )
                assert read_json_field(invoke_resp2, "status") == "success"

                print("[e2e-container-local] Deleting managed function and verifying cleanup")
                subprocess.run(
                    [
                        "curl",
                        "-fsS",
                        "-X",
                        "DELETE",
                        self._api.function_url(function_name),
                    ],
                    check=True,
                )
                self._wait_for_containers(function_slug, 0)

                import httpx as _httpx

                status = _httpx.get(self._api.function_url(function_name), timeout=5).status_code
                if status != 404:
                    raise RuntimeError(
                        f"Expected 404 after function delete, got {status}"
                    )

            print("[e2e-container-local] PASS: container-local managed DEPLOYMENT flow")
        finally:
            self._cleanup(cp_proc, function_slug, function_name)
