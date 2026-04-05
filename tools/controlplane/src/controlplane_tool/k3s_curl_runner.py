"""
k3s_curl_runner.py

K3sCurlRunner: k3s curl compatibility workflow inside a VM-backed environment.

Mirrors the logic of the deleted e2e-k3s-curl-backend.sh (M11).
"""
from __future__ import annotations

from controlplane_tool.console import console, phase, step, success, warning, skip, fail, status

import base64
import json
import os
from pathlib import Path

from tenacity import RetryError, Retrying, stop_after_attempt, wait_fixed
from typing import TYPE_CHECKING

from controlplane_tool.registry_runtime import LocalRegistry
from controlplane_tool.scenario_helpers import (
    function_family as _function_family,
    function_image as _function_image,
    function_payload as _function_payload,
    function_runtime as _function_runtime,
    resolve_scenario as _resolve_scenario,
    selected_functions as _selected_functions,
)
from controlplane_tool.shell_backend import SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest, vm_request_from_env

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


class K3sCurlRunner:
    """Run the k3s curl compatibility workflow inside a VM-backed environment.

    Mirrors the logic of the deleted e2e-k3s-curl-backend.sh.
    """

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas-e2e",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.registry = LocalRegistry(local_registry)
        self.runtime = runtime
        self._shell = SubprocessShell()
        self._vm = VmOrchestrator(self.repo_root, shell=self._shell)
        self._cached_service_ip: str | None = None

    def _vm_exec(self, command: str) -> str:
        result = self._vm.remote_exec(self.vm_request, command=command)
        if result.return_code != 0:
            raise RuntimeError(f"VM exec failed: {command!r}\n{result.stderr}")
        return result.stdout.strip()

    @property
    def _remote_dir(self) -> str:
        return self._vm.remote_project_dir(self.vm_request)

    @property
    def _control_image(self) -> str:
        return self.registry.control_plane_image()

    @property
    def _runtime_image(self) -> str:
        return self.registry.function_runtime_image()

    def _build_jars(self) -> None:
        step(f"Building jars (runtime={self.runtime})")
        if self.runtime == "rust":
            self._vm_exec(
                f"cd {self._remote_dir} && ./gradlew :function-runtime:bootJar --no-daemon -q"
            )
        else:
            self._vm_exec(
                f"cd {self._remote_dir} && "
                f"./scripts/controlplane.sh jar --profile k8s -- --quiet"
            )
            self._vm_exec(
                f"cd {self._remote_dir} && ./gradlew :function-runtime:bootJar --no-daemon -q"
            )

    def _build_images(self) -> None:
        step("Building and pushing core images")
        if self.runtime == "rust":
            self._vm_exec(
                f"cd {self._remote_dir} && "
                f"cargo build --release --manifest-path control-plane-rust/Cargo.toml 2>/dev/null || true"
            )
            self._vm_exec(
                f"cd {self._remote_dir} && "
                f"sudo docker build -f control-plane-rust/Dockerfile -t {self._control_image} control-plane-rust/"
            )
        else:
            self._vm_exec(
                f"cd {self._remote_dir} && "
                f"sudo docker build -f control-plane/Dockerfile -t {self._control_image} control-plane/"
            )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"sudo docker build -t {self._runtime_image} function-runtime/"
        )
        self._vm_exec(f"sudo docker push {self._control_image}")
        self._vm_exec(f"sudo docker push {self._runtime_image}")

    def _deploy_platform(self) -> None:
        step("Deploying platform to k3s")
        self._vm_exec(
            f"kubectl create namespace {self.namespace} --dry-run=client -o yaml | kubectl apply -f -"
        )
        cp_repo = self._control_image.rsplit(":", 1)[0]
        cp_tag = self._control_image.rsplit(":", 1)[-1]
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"helm upgrade --install control-plane helm/nanofaas "
            f"-n {self.namespace} "
            f"--set controlPlane.image.repository={cp_repo} "
            f"--set controlPlane.image.tag={cp_tag} "
            f"--set controlPlane.image.pullPolicy=Always "
            f"--set syncQueue.enabled=false "
            f"--wait --timeout 3m"
        )
        self._vm_exec(
            f"kubectl rollout restart deployment/control-plane -n {self.namespace} || true"
        )
        fr_repo = self._runtime_image.rsplit(":", 1)[0]
        fr_tag = self._runtime_image.rsplit(":", 1)[-1]
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"helm upgrade --install function-runtime helm/nanofaas-runtime "
            f"-n {self.namespace} "
            f"--set functionRuntime.image.repository={fr_repo} "
            f"--set functionRuntime.image.tag={fr_tag} "
            f"--set functionRuntime.image.pullPolicy=Always "
            f"--wait --timeout 3m 2>/dev/null || "
            f"kubectl apply -n {self.namespace} -f "
            f"<(cat <<'YAML'\n"
            f"apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: function-runtime\n"
            f"  namespace: {self.namespace}\n  labels:\n    app: function-runtime\n"
            f"spec:\n  replicas: 1\n  selector:\n    matchLabels:\n      app: function-runtime\n"
            f"  template:\n    metadata:\n      labels:\n        app: function-runtime\n"
            f"    spec:\n      containers:\n      - name: function-runtime\n"
            f"        image: {self._runtime_image}\n        imagePullPolicy: Always\n"
            f"        ports:\n        - containerPort: 8080\nYAML\n)"
        )

    def _wait_for_deployment(self, name: str, timeout: int = 180) -> None:
        self._vm_exec(
            f"kubectl rollout status deployment/{name} -n {self.namespace} --timeout={timeout}s"
        )

    def _control_plane_service_ip(self) -> str:
        if self._cached_service_ip is None:
            ip = self._vm_exec(
                f"kubectl get svc -n {self.namespace} control-plane "
                f"-o jsonpath='{{.spec.clusterIP}}'"
            )
            if not ip:
                raise RuntimeError("Failed to resolve control-plane ClusterIP")
            self._cached_service_ip = ip
        return self._cached_service_ip

    def _verify_health(self) -> None:
        step("Verifying control-plane health")
        service_ip = self._control_plane_service_ip()
        self._vm_exec(
            f"curl -sf http://{service_ip}:8081/actuator/health | grep -q '\"status\":\"UP\"'"
        )

    def _build_function_image(
        self,
        fn_key: str,
        fn_image: str,
        resolved: "ResolvedScenario | None",
    ) -> None:
        if resolved is None:
            return
        rt = _function_runtime(fn_key, resolved)
        family = _function_family(fn_key, resolved)
        if family is None:
            return
        remote = self._remote_dir
        if rt == "java":
            self._vm_exec(
                f"cd {remote} && "
                f"./gradlew :examples:java:{family}:bootBuildImage "
                f"-PfunctionImage={fn_image} --no-daemon -q"
            )
        elif rt == "java-lite":
            self._vm_exec(
                f"cd {remote} && "
                f"sudo docker build -t {fn_image} -f examples/java/{family}-lite/Dockerfile ."
            )
        elif rt in ("go", "python", "exec"):
            lang = "go" if rt == "go" else ("python" if rt == "python" else "bash")
            self._vm_exec(
                f"cd {remote} && "
                f"sudo docker build -t {fn_image} -f examples/{lang}/{family}/Dockerfile ."
            )
        else:
            raise RuntimeError(f"Unsupported function runtime: {rt!r}")

    def _kubectl_curl(self, method: str, path: str, body_json: str | None = None) -> str:
        url = f"http://{self._control_plane_service_ip()}:8080{path}"
        if body_json:
            b64 = base64.b64encode(body_json.encode()).decode()
            return self._vm_exec(
                f"echo '{b64}' | base64 -d | "
                f"curl -s --max-time 35 -X {method} '{url}' "
                f"-H 'Content-Type: application/json' --data-binary @-"
            )
        return self._vm_exec(f"curl -s --max-time 35 -X {method} '{url}'")

    def _register_function(self, fn_key: str, fn_image: str) -> None:
        spec = json.dumps(
            {
                "name": fn_key,
                "image": fn_image,
                "timeoutMs": 5000,
                "concurrency": 2,
                "queueSize": 20,
                "maxRetries": 3,
                "executionMode": "DEPLOYMENT",
            }
        )
        self._kubectl_curl("POST", "/v1/functions", spec)

    def _invoke_function(self, fn_key: str, payload: str) -> None:
        response = self._kubectl_curl("POST", f"/v1/functions/{fn_key}:invoke", payload)
        if '"status":"success"' not in response and '"status": "success"' not in response:
            raise RuntimeError(f"Sync invoke did not return success for {fn_key}: {response}")

    def _enqueue_function(self, fn_key: str, payload: str) -> str:
        response = self._kubectl_curl("POST", f"/v1/functions/{fn_key}:enqueue", payload)
        parsed: dict = {}
        try:
            parsed = json.loads(response)
        except Exception:
            pass
        exec_id = parsed.get("executionId") or parsed.get("execution_id", "")
        if not exec_id:
            raise RuntimeError(f"enqueue did not return executionId for {fn_key}: {response}")
        return str(exec_id)

    def _poll_execution(self, exec_id: str, max_polls: int = 20) -> None:
        try:
            for attempt in Retrying(
                stop=stop_after_attempt(max_polls),
                wait=wait_fixed(1),
            ):
                with attempt:
                    response = self._kubectl_curl("GET", f"/v1/executions/{exec_id}")
                    if '"status":"success"' not in response and '"status": "success"' not in response:
                        raise RuntimeError("execution not complete yet")
        except RetryError as exc:
            raise RuntimeError(f"Async execution did not complete: executionId={exec_id}") from exc

    def _verify_prometheus_metrics(self) -> None:
        step("Verifying Prometheus metrics")
        metrics = self._vm_exec(
            f"curl -sf http://{self._control_plane_service_ip()}:8081/actuator/prometheus"
        )
        for metric in (
            "function_enqueue_total",
            "function_success_total",
            "function_queue_depth",
            "function_inFlight",
        ):
            if metric not in metrics:
                raise RuntimeError(f"Prometheus metric {metric!r} not found")

    def _run_function_workflow(
        self,
        fn_key: str,
        resolved: "ResolvedScenario | None",
    ) -> None:
        fn_image = _function_image(fn_key, resolved, self._runtime_image)
        step(f"Running function workflow for '{fn_key}'")
        self._build_function_image(fn_key, fn_image, resolved)
        if resolved is not None:
            self._vm_exec(f"sudo docker push {fn_image}")
        self._register_function(fn_key, fn_image)
        payload = _function_payload(fn_key, resolved)
        self._invoke_function(fn_key, payload)
        exec_id = self._enqueue_function(fn_key, payload)
        self._poll_execution(exec_id)

    def run(self, scenario_file: Path | None = None) -> None:
        resolved = _resolve_scenario(scenario_file)
        skip_bootstrap = os.getenv("E2E_SKIP_VM_BOOTSTRAP", "").lower() == "true"
        if not skip_bootstrap:
            raise RuntimeError(
                "K3sCurlRunner requires VM to be bootstrapped. "
                "Set E2E_SKIP_VM_BOOTSTRAP=true and ensure VM is running, or use E2eRunner."
            )

        self._build_jars()
        self._build_images()
        self._deploy_platform()
        self._wait_for_deployment("control-plane", 180)
        self._wait_for_deployment("function-runtime", 120)
        self._verify_health()

        functions = _selected_functions(resolved)
        for fn_key in functions:
            self._run_function_workflow(fn_key, resolved)

        self._verify_prometheus_metrics()
        success("k3s curl E2E workflow")
