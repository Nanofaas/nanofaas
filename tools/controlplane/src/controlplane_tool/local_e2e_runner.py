"""
local_e2e_runner.py

Python implementations of local E2E scenario workflows.

Replaces:
  - scripts/lib/e2e-container-local-backend.sh  (M9)
  - scripts/lib/e2e-deploy-host-backend.sh       (M9)

These classes own the full workflow lifecycle for each scenario and are
invoked as a CLI subcommand from e2e_commands.py:

    controlplane-tool local-e2e run container-local [--scenario-file PATH]
    controlplane-tool local-e2e run deploy-host     [--scenario-file PATH]
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from controlplane_tool.control_plane_api import ControlPlaneApi
from controlplane_tool.runtime_primitives import (
    CommandRunner,
    ContainerRuntimeOps,
    read_json_field,
    wrap_payload,
    write_json_file,
)
from controlplane_tool.scenario_runtime import (
    FakeControlPlane,
    select_container_runtime,
    wait_for_http_any_status,
    wait_for_http_ok,
)

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


# ---------------------------------------------------------------------------
# container-local runner
# ---------------------------------------------------------------------------


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

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

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

    def _resolve_scenario(self, scenario_file: Path | None) -> "ResolvedScenario | None":
        if scenario_file is None:
            return None
        from controlplane_tool.scenario_loader import load_scenario_file

        return load_scenario_file(scenario_file)

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
        import time
        for _ in range(max_attempts):
            if self._managed_container_count(function_slug) == expected:
                return
            time.sleep(1)
        raise RuntimeError(f"Timed out waiting for {expected} managed containers for '{function_slug}'")

    def _cleanup(self, cp_proc: subprocess.Popen[str] | None, function_slug: str, function_name: str) -> None:
        adapter = self.runtime_adapter or "docker"
        try:
            import urllib.request
            urllib.request.urlopen(
                urllib.request.Request(
                    f"{self._api.base_url}/v1/functions/{function_name}",
                    method="DELETE",
                ),
                timeout=5,
            )
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

    # ------------------------------------------------------------------
    # main entry point
    # ------------------------------------------------------------------

    def run(self, scenario_file: Path | None = None) -> None:
        import urllib.request

        resolved = self._resolve_scenario(scenario_file)
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

                import urllib.request as ur
                import urllib.error

                req = ur.Request(self._api.function_url(function_name), method="GET")
                try:
                    with ur.urlopen(req, timeout=5) as resp:
                        status = resp.status
                except urllib.error.HTTPError as exc:
                    status = exc.code
                if status != 404:
                    raise RuntimeError(
                        f"Expected 404 after function delete, got {status}"
                    )

            print("[e2e-container-local] PASS: container-local managed DEPLOYMENT flow")
        finally:
            self._cleanup(cp_proc, function_slug, function_name)


# ---------------------------------------------------------------------------
# deploy-host runner
# ---------------------------------------------------------------------------


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

    def _run(self, command: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            cwd=str(self.repo_root),
            text=True,
            capture_output=True,
            check=check,
            env=os.environ.copy(),
        )

    def _resolve_functions(self, resolved: "ResolvedScenario | None") -> list[tuple[str, Path | None]]:
        """Return [(function_key, example_dir_or_None)] for selected functions."""
        if resolved is None:
            return [("deploy-e2e", None)]
        return [(fn.key, fn.example_dir) for fn in resolved.functions]

    def _resolve_scenario(self, scenario_file: Path | None) -> "ResolvedScenario | None":
        if scenario_file is None:
            return None
        from controlplane_tool.scenario_loader import load_scenario_file

        return load_scenario_file(scenario_file)

    def _build_cli(self, *, skip: bool = False) -> None:
        if skip:
            if not self._cli_bin.exists():
                raise RuntimeError(f"CLI binary not found at {self._cli_bin}")
            return
        print("[e2e-deploy-host] Building nanofaas CLI on host...")
        self._run(["./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"])
        if not self._cli_bin.exists():
            raise RuntimeError(f"CLI binary not found at {self._cli_bin}")

    def _start_registry(self, container_name: str, docker: str = "docker") -> None:
        print(f"[e2e-deploy-host] Starting local registry ({container_name}) on port {self.registry_port}")
        subprocess.run([docker, "rm", "-f", container_name], capture_output=True, check=False)
        subprocess.run(
            [docker, "run", "-d", "--name", container_name, "-p", f"{self.registry_port}:5000", "registry:2"],
            check=True,
            capture_output=True,
        )
        if not wait_for_http_ok(f"http://127.0.0.1:{self.registry_port}/v2/", max_attempts=40):
            raise RuntimeError(f"Registry did not become ready on port {self.registry_port}")

    def _stop_registry(self, container_name: str, docker: str = "docker") -> None:
        subprocess.run([docker, "rm", "-f", container_name], capture_output=True, check=False)

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
        import urllib.request

        url = f"http://127.0.0.1:{self.registry_port}/v2/{image_repo}/tags/list"
        with urllib.request.urlopen(url, timeout=10) as resp:
            tags_json = resp.read().decode("utf-8")
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
        skip_cli_build: bool = False,
    ) -> None:
        import time

        resolved = self._resolve_scenario(scenario_file)
        selected_functions = self._resolve_functions(resolved)

        docker = select_container_runtime() or "docker"
        tag = f"e2e-{int(time.time())}"
        registry_container = f"nanofaas-deploy-e2e-registry-{int(time.time())}"

        self._build_cli(skip=skip_cli_build)

        work_dir = Path(tempfile.mkdtemp(prefix="nanofaas-deploy-host-e2e."))
        request_body_path = work_dir / "function-request.json"
        fake_cp = FakeControlPlane(self.control_plane_port, request_body_path)

        try:
            self._start_registry(registry_container, docker)
            fake_cp.start(work_dir)
            if not wait_for_http_any_status(f"http://127.0.0.1:{self.control_plane_port}/", max_attempts=30):
                raise RuntimeError(f"Fake control-plane did not start on port {self.control_plane_port}")

            cp_url = f"http://127.0.0.1:{self.control_plane_port}"

            for function_key, example_dir in selected_functions:
                image_repo = f"nanofaas/{function_key}"
                fn_yaml = self._write_function_yaml(work_dir, function_key, image_repo, tag, example_dir)
                request_body_path.unlink(missing_ok=True)
                subprocess.run(
                    [str(self._cli_bin), "--endpoint", cp_url, "deploy", "-f", str(fn_yaml)],
                    check=True,
                    cwd=str(self.repo_root),
                )
                self._verify_registry_push(image_repo, tag)
                self._verify_register_request(request_body_path, function_key, image_repo, tag)

            print("[e2e-deploy-host] Deploy host E2E: PASSED")
        finally:
            fake_cp.stop()
            self._stop_registry(registry_container, docker)
            import shutil
            shutil.rmtree(work_dir, ignore_errors=True)
