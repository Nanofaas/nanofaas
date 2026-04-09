"""
k3s_curl_runner.py

K3sCurlRunner: curl-based verifier for the shared k3s VM scenario.
"""
from __future__ import annotations

from controlplane_tool.console import phase, step, success

import base64
import json
import os
import shlex
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
from controlplane_tool.scenario_tasks import (
    build_core_images_vm_script,
    build_function_images_vm_script,
    helm_upgrade_install_vm_script,
    helm_uninstall_vm_script,
    kubectl_create_namespace_vm_script,
    kubectl_delete_namespace_vm_script,
    kubectl_rollout_status_vm_script,
)
from controlplane_tool.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest, vm_request_from_env

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


class K3sCurlRunner:
    """Run curl-based verification against the shared k3s VM-backed deployment."""

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas-e2e",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        shell: ShellBackend | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.registry = LocalRegistry(local_registry)
        self.runtime = runtime
        self._shell = shell or SubprocessShell()
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
    def _kubeconfig_path(self) -> str:
        return self._vm.kubeconfig_path(self.vm_request)

    @property
    def _control_image(self) -> str:
        return self.registry.control_plane_image()

    @property
    def _runtime_image(self) -> str:
        return self.registry.function_runtime_image()

    def _split_image(self, image: str) -> tuple[str, str]:
        return self.registry.split_image_ref(image)

    def _control_plane_helm_values(self) -> dict[str, str]:
        repository, tag = self._split_image(self._control_image)
        callback_url = (
            f"http://control-plane.{self.namespace}.svc.cluster.local:8080/v1/internal/executions"
        )
        values = {
            "namespace.create": "false",
            "namespace.name": self.namespace,
            "controlPlane.image.repository": repository,
            "controlPlane.image.tag": tag,
            "controlPlane.image.pullPolicy": "Always",
            "demos.enabled": "false",
            "prometheus.create": "false",
        }
        extra_env = [
            ("NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND", "k8s"),
            ("NANOFAAS_K8S_CALLBACK_URL", callback_url),
            ("SYNC_QUEUE_ENABLED", "true"),
            ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
            ("SYNC_QUEUE_ADMISSION_ENABLED", "false"),
            ("SYNC_QUEUE_MAX_DEPTH", "1"),
            ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
            ("SYNC_QUEUE_MAX_ESTIMATED_WAIT", "2s"),
            ("SYNC_QUEUE_MAX_QUEUE_WAIT", "5s"),
            ("SYNC_QUEUE_RETRY_AFTER_SECONDS", "2"),
            ("SYNC_QUEUE_THROUGHPUT_WINDOW", "10s"),
            ("SYNC_QUEUE_PER_FUNCTION_MIN_SAMPLES", "1"),
        ]
        for index, (name, value) in enumerate(extra_env):
            values[f"controlPlane.extraEnv[{index}].name"] = name
            values[f"controlPlane.extraEnv[{index}].value"] = value
        return values

    def _function_runtime_helm_values(self) -> dict[str, str]:
        repository, tag = self._split_image(self._runtime_image)
        return {
            "functionRuntime.image.repository": repository,
            "functionRuntime.image.tag": tag,
            "functionRuntime.image.pullPolicy": "Always",
        }

    def _build_jars(self) -> None:
        phase("Build")
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
        self._vm_exec(
            build_core_images_vm_script(
                remote_dir=self._remote_dir,
                control_image=self._control_image,
                runtime_image=self._runtime_image,
                runtime=self.runtime,
                mode="docker",
                sudo=True,
                build_jars=True,
            )
        )

    def _deploy_platform(self) -> None:
        phase("Deploy")
        step("Deploying platform to k3s")
        self._vm_exec(
            kubectl_create_namespace_vm_script(
                remote_dir=self._remote_dir,
                namespace=self.namespace,
                kubeconfig_path=self._kubeconfig_path,
            )
        )
        self._vm_exec(
            helm_upgrade_install_vm_script(
                remote_dir=self._remote_dir,
                release="control-plane",
                chart="helm/nanofaas",
                namespace=self.namespace,
                values=self._control_plane_helm_values(),
                kubeconfig_path=self._kubeconfig_path,
                timeout="5m",
            )
        )
        self._vm_exec(
            helm_upgrade_install_vm_script(
                remote_dir=self._remote_dir,
                release="function-runtime",
                chart="helm/nanofaas-runtime",
                namespace=self.namespace,
                values=self._function_runtime_helm_values(),
                kubeconfig_path=self._kubeconfig_path,
                timeout="3m",
            )
        )

    def _wait_for_deployment(self, name: str, timeout: int = 180) -> None:
        self._vm_exec(
            kubectl_rollout_status_vm_script(
                remote_dir=self._remote_dir,
                namespace=self.namespace,
                deployment=name,
                kubeconfig_path=self._kubeconfig_path,
                timeout=timeout,
            )
        )

    def _await_managed_function_ready(self, fn_key: str, max_polls: int = 60) -> None:
        deployment_name = f"fn-{fn_key}"

        def _is_ready() -> None:
            ready_replicas = self._vm_exec(
                f"KUBECONFIG={shlex.quote(self._kubeconfig_path)} "
                f"kubectl get deployment {deployment_name} -n {self.namespace} "
                f"-o jsonpath='{{.status.readyReplicas}}' 2>/dev/null || true"
            ).strip()
            try:
                ready_count = int(ready_replicas)
            except ValueError:
                ready_count = 0
            if ready_count < 1:
                raise RuntimeError(f"managed deployment {deployment_name} has no ready replicas yet")

            endpoints = self._vm_exec(
                f"KUBECONFIG={shlex.quote(self._kubeconfig_path)} "
                f"kubectl get endpoints {deployment_name} -n {self.namespace} "
                f"-o jsonpath='{{range .subsets[*].addresses[*]}}{{.ip}} {{end}}' 2>/dev/null || true"
            ).strip()
            if not endpoints:
                raise RuntimeError(f"managed service {deployment_name} has no ready endpoints yet")

        try:
            for attempt in Retrying(
                stop=stop_after_attempt(max_polls),
                wait=wait_fixed(2),
            ):
                with attempt:
                    _is_ready()
        except RetryError as exc:
            raise RuntimeError(f"managed function {deployment_name} did not become ready in time") from exc

    def _control_plane_service_ip(self) -> str:
        if self._cached_service_ip is None:
            ip = self._vm_exec(
                f"KUBECONFIG={shlex.quote(self._kubeconfig_path)} "
                f"kubectl get svc -n {self.namespace} control-plane "
                f"-o jsonpath='{{.spec.clusterIP}}'"
            )
            if not ip:
                raise RuntimeError("Failed to resolve control-plane ClusterIP")
            self._cached_service_ip = ip
        return self._cached_service_ip

    def _verify_health(self) -> None:
        phase("Verify")
        step("Verifying control-plane health")
        service_ip = self._control_plane_service_ip()
        self._vm_exec(
            f"curl -sf http://{service_ip}:8081/actuator/health | grep -q '\"status\":\"UP\"'"
        )

    def build_selected_function_images(self, resolved: "ResolvedScenario | None") -> None:
        functions: list[tuple[str, str, str]] = []
        for fn_key in _selected_functions(resolved):
            runtime_kind = _function_runtime(fn_key, resolved)
            family = _function_family(fn_key, resolved)
            if family is None:
                continue
            if runtime_kind == "fixture":
                continue
            functions.append(
                (_function_image(fn_key, resolved, self._runtime_image), runtime_kind, family)
            )

        if not functions:
            return

        step("Building and pushing selected function images")
        self._vm_exec(
            build_function_images_vm_script(
                remote_dir=self._remote_dir,
                functions=functions,
                sudo=True,
                push=True,
            )
        )

    def verify_existing_stack(self, resolved: "ResolvedScenario | None") -> None:
        self._verify_health()
        for fn_key in _selected_functions(resolved):
            self._run_function_workflow(fn_key, resolved)
        self._verify_prometheus_metrics()

    def cleanup_platform(self) -> None:
        step("Cleaning up shared k3s platform")
        try:
            self._vm_exec(
                helm_uninstall_vm_script(
                    remote_dir=self._remote_dir,
                    release="function-runtime",
                    namespace=self.namespace,
                    kubeconfig_path=self._kubeconfig_path,
                )
            )
        except RuntimeError:
            pass
        try:
            self._vm_exec(
                helm_uninstall_vm_script(
                    remote_dir=self._remote_dir,
                    release="control-plane",
                    namespace=self.namespace,
                    kubeconfig_path=self._kubeconfig_path,
                )
            )
        except RuntimeError:
            pass
        try:
            self._vm_exec(
                kubectl_delete_namespace_vm_script(
                    remote_dir=self._remote_dir,
                    namespace=self.namespace,
                    kubeconfig_path=self._kubeconfig_path,
                )
            )
        except RuntimeError:
            pass

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
        last_response = ""
        try:
            for attempt in Retrying(
                stop=stop_after_attempt(15),
                wait=wait_fixed(2),
            ):
                with attempt:
                    response = self._kubectl_curl("POST", f"/v1/functions/{fn_key}:invoke", payload)
                    last_response = response
                    if '"status":"success"' not in response and '"status": "success"' not in response:
                        raise RuntimeError(
                            f"Sync invoke did not return success for {fn_key}: {response}"
                        )
        except RetryError as exc:
            raise RuntimeError(
                f"Sync invoke did not return success for {fn_key}: {last_response}"
            ) from exc

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
        self._register_function(fn_key, fn_image)
        self._await_managed_function_ready(fn_key)
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

        self._build_images()
        self.build_selected_function_images(resolved)
        self._deploy_platform()
        self._wait_for_deployment("nanofaas-control-plane", 180)
        self._wait_for_deployment("function-runtime", 120)
        self.verify_existing_stack(resolved)
        success("k3s curl E2E workflow")
