#!/usr/bin/env python3
"""
E2E Autoscaling Experiment

Verifies InternalScaler scales up under load and scales back down to 0
when load stops.

Prerequisites:
  - nanofaas deployed via ./scripts/e2e-k3s-helm.sh (VM running)
  - k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/)

Usage:
  uv run --project tools/controlplane --locked python experiments/autoscaling.py
  # or via HelmStackRunner which imports AutoscalingExperiment directly
"""
from __future__ import annotations

import contextlib
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path


def _project_root() -> Path:
    return Path(__file__).resolve().parent.parent


class AutoscalingExperiment:
    """Python port of experiments/e2e-autoscaling.sh.

    Runs 5 phases:
      A. Register function with INTERNAL scaling config
      B. Verify baseline replicas settle
      C. Apply k6 load and observe scale-up
      D. Wait for scale-down to 0
      E. Report results
    """

    def __init__(
        self,
        *,
        vm_name: str | None = None,
        namespace: str | None = None,
        function_name: str | None = None,
        function_image: str | None = None,
        control_plane_runtime: str | None = None,
        repo_root: Path | None = None,
    ) -> None:
        self.repo_root = repo_root or _project_root()
        self.vm_name = vm_name or os.getenv("VM_NAME", "nanofaas-e2e")
        self.namespace = namespace or os.getenv("NAMESPACE", "nanofaas")
        self.function_name = function_name or os.getenv("FUNCTION_NAME", "word-stats-java")
        self.deploy_name = f"fn-{self.function_name}"
        self.control_plane_runtime = control_plane_runtime or os.getenv(
            "CONTROL_PLANE_RUNTIME", "java"
        )

        # Resolve function image
        if function_image:
            self.function_image = function_image
        elif os.getenv("FUNCTION_IMAGE"):
            self.function_image = os.environ["FUNCTION_IMAGE"]
        else:
            tag = os.getenv("FUNCTION_IMAGE_TAG") or os.getenv("TAG") or self._project_version()
            self.function_image = f"localhost:5000/nanofaas/java-word-stats:{tag}"

        self._pass_count = 0
        self._fail_count = 0
        self._max_replicas_observed = 0

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Run all phases. Raises SystemExit(1) on failure."""
        cluster_api_url = self._cluster_api_url()
        with self._host_api_url_context() as host_api_url:
            self._preflight(host_api_url)
            self._phase_a_register(cluster_api_url)
            self._phase_b_baseline()
            self._phase_c_load_and_scaleup(host_api_url)
            self._phase_d_scaledown()
            self._phase_e_report()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _project_version(self) -> str:
        build_gradle = self.repo_root / "build.gradle"
        if build_gradle.exists():
            for line in build_gradle.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if stripped.startswith("version") and "=" in stripped:
                    raw = stripped.split("=", 1)[1].strip().strip("'\"")
                    if raw:
                        return f"v{raw}"
        return "v0.0.0"

    def _vm_exec(self, command: str) -> str:
        result = subprocess.run(
            ["multipass", "exec", self.vm_name, "--", "bash", "-lc", command],
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()

    def _resolve_public_host(self) -> str:
        env_url = os.getenv("E2E_VM_HOST") or os.getenv("E2E_PUBLIC_HOST")
        if env_url:
            return env_url.rstrip("/").removeprefix("http://").removeprefix("https://")
        ip = self._vm_exec(
            f"multipass info {self.vm_name} --format json 2>/dev/null "
            f"| python3 -c \"import json,sys; d=json.load(sys.stdin); "
            f"print(d['info']['{self.vm_name}']['ipv4'][0])\""
        )
        if not ip:
            ip = self._vm_exec(
                f"multipass info {self.vm_name} 2>/dev/null | grep IPv4 | awk '{{print $2}}'"
            )
        return ip

    def _kubeconfig_env_prefix(self) -> str:
        kubeconfig_path = os.getenv("E2E_KUBECONFIG_PATH", "").strip()
        if not kubeconfig_path:
            return ""
        return f"KUBECONFIG={kubeconfig_path} "

    def _control_plane_service_type(self) -> str:
        service_type = self._vm_exec(
            f"{self._kubeconfig_env_prefix()}kubectl get svc control-plane -n {self.namespace} "
            f"-o jsonpath='{{.spec.type}}' 2>/dev/null"
        ).strip()
        return service_type or "ClusterIP"

    def _control_plane_cluster_ip(self) -> str:
        cluster_ip = self._vm_exec(
            f"{self._kubeconfig_env_prefix()}kubectl get svc control-plane -n {self.namespace} "
            f"-o jsonpath='{{.spec.clusterIP}}' 2>/dev/null"
        ).strip()
        if not cluster_ip:
            raise RuntimeError("Failed to resolve control-plane ClusterIP")
        return cluster_ip

    def _control_plane_http_node_port(self) -> str:
        return self._vm_exec(
            f"{self._kubeconfig_env_prefix()}kubectl get svc control-plane -n {self.namespace} "
            f"-o jsonpath='{{.spec.ports[0].nodePort}}' 2>/dev/null"
        ).strip()

    def _cluster_api_url(self) -> str:
        return f"http://{self._control_plane_cluster_ip()}:8080"

    def _pick_port_forward_port(self) -> int:
        try:
            from controlplane_tool.net_utils import pick_local_port

            return pick_local_port(preferred=18080)
        except Exception:
            import socket

            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.bind(("127.0.0.1", 0))
                return int(sock.getsockname()[1])

    def _rewrite_kubeconfig_server(self, kubeconfig_path: Path, public_host: str) -> None:
        content = kubeconfig_path.read_text(encoding="utf-8")
        rewritten = content.replace(
            "server: https://127.0.0.1:6443",
            f"server: https://{public_host}:6443",
        ).replace(
            "server: https://localhost:6443",
            f"server: https://{public_host}:6443",
        )
        kubeconfig_path.write_text(rewritten, encoding="utf-8")

    def _export_host_kubeconfig(self, public_host: str) -> Path:
        fd, temp_path = tempfile.mkstemp(prefix="nanofaas-autoscaling-", suffix=".yaml")
        os.close(fd)
        destination = Path(temp_path)

        remote_kubeconfig = os.getenv("E2E_KUBECONFIG_PATH", "/home/ubuntu/.kube/config")
        vm_lifecycle = os.getenv("E2E_VM_LIFECYCLE", "").strip().lower()
        vm_user = os.getenv("E2E_VM_USER", "ubuntu")
        if vm_lifecycle == "external":
            result = subprocess.run(
                ["scp", f"{vm_user}@{public_host}:{remote_kubeconfig}", str(destination)],
                capture_output=True,
                text=True,
            )
        else:
            result = subprocess.run(
                ["multipass", "transfer", f"{self.vm_name}:{remote_kubeconfig}", str(destination)],
                capture_output=True,
                text=True,
            )
        if result.returncode != 0:
            destination.unlink(missing_ok=True)
            raise RuntimeError(result.stderr or result.stdout or "Failed to export kubeconfig")
        self._rewrite_kubeconfig_server(destination, public_host)
        return destination

    def _start_port_forward(self, kubeconfig_path: Path, local_port: int) -> subprocess.Popen[str]:
        return subprocess.Popen(
            [
                shutil.which("kubectl") or "kubectl",
                "--kubeconfig",
                str(kubeconfig_path),
                "-n",
                self.namespace,
                "port-forward",
                "svc/control-plane",
                f"{local_port}:8080",
                "--address",
                "127.0.0.1",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def _wait_for_port_forward(self, proc: subprocess.Popen[str], local_url: str) -> None:
        deadline = time.time() + 10
        probe_url = f"{local_url}/v1/functions"
        while time.time() < deadline:
            if proc.poll() is not None:
                stdout, stderr = proc.communicate()
                raise RuntimeError(
                    stderr.strip() or stdout.strip() or "kubectl port-forward exited unexpectedly"
                )
            if self._is_url_reachable(probe_url):
                return
            time.sleep(0.2)
        proc.terminate()
        try:
            stdout, stderr = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, stderr = proc.communicate()
        raise RuntimeError(
            stderr.strip() or stdout.strip() or f"Timed out waiting for port-forward to {probe_url}"
        )

    @contextlib.contextmanager
    def _host_api_url_context(self):
        service_type = self._control_plane_service_type()
        if service_type == "NodePort":
            host = self._resolve_public_host()
            port = self._control_plane_http_node_port() or "30080"
            yield f"http://{host}:{port}"
            return

        kubectl = shutil.which("kubectl")
        if not kubectl:
            raise RuntimeError("kubectl is required on the host to reach a ClusterIP control-plane service")

        public_host = self._resolve_public_host()
        kubeconfig_path = self._export_host_kubeconfig(public_host)
        local_port = self._pick_port_forward_port()
        proc = self._start_port_forward(kubeconfig_path, local_port)
        local_url = f"http://127.0.0.1:{local_port}"
        try:
            self._wait_for_port_forward(proc, local_url)
            yield local_url
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
            kubeconfig_path.unlink(missing_ok=True)

    def _get_ready_replicas(self, deploy_name: str) -> int:
        raw = self._vm_exec(
            f"{self._kubeconfig_env_prefix()}kubectl get deployment {deploy_name} -n {self.namespace} "
            f"-o jsonpath='{{.status.readyReplicas}}' 2>/dev/null || echo 0"
        )
        try:
            return int(raw)
        except (ValueError, TypeError):
            return 0

    def _get_desired_replicas(self, deploy_name: str) -> int:
        raw = self._vm_exec(
            f"{self._kubeconfig_env_prefix()}kubectl get deployment {deploy_name} -n {self.namespace} "
            f"-o jsonpath='{{.spec.replicas}}' 2>/dev/null || echo 0"
        )
        try:
            return int(raw)
        except (ValueError, TypeError):
            return 0

    def _log(self, msg: str) -> None:
        print(f"[autoscale] {msg}", flush=True)

    def _pass(self, msg: str) -> None:
        self._pass_count += 1
        self._log(f"  PASS: {msg}")

    def _fail(self, msg: str) -> None:
        self._fail_count += 1
        self._log(f"  FAIL: {msg}")

    def _is_url_reachable(self, url: str) -> bool:
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                return resp.status < 400
        except Exception:
            return False

    # ------------------------------------------------------------------
    # Phases
    # ------------------------------------------------------------------

    def _preflight(self, nanofaas_url: str) -> None:
        self._log("Pre-flight checks...")

        if not shutil.which("k6"):
            self._log(
                "ERROR: k6 is not installed. "
                "Install from https://grafana.com/docs/k6/latest/set-up/install-k6/"
            )
            sys.exit(1)

        if not self._is_url_reachable(f"{nanofaas_url}/v1/functions"):
            self._log(f"ERROR: Cannot reach {nanofaas_url}/v1/functions")
            self._log("Is nanofaas running? Run ./scripts/e2e-k3s-helm.sh first.")
            sys.exit(1)

        self._log(f"  API reachable at {nanofaas_url}")
        self._log(f"  Runtime: {self.control_plane_runtime}")

    def _phase_a_register(self, nanofaas_url: str | None = None) -> None:
        nanofaas_url = nanofaas_url or self._cluster_api_url()
        self._log("")
        self._log("━━━ Phase A: Register function with scaling config ━━━")

        self._log(f"  Deleting existing {self.function_name} (if any)...")
        self._vm_exec(
            f"curl -sf -X DELETE '{nanofaas_url}/v1/functions/{self.function_name}' "
            f">/dev/null 2>&1 || true"
        )
        time.sleep(2)

        self._log(
            f"  Registering {self.function_name} with INTERNAL scaling "
            f"(minReplicas=0, maxReplicas=5)..."
        )
        payload = (
            f'{{"name":"{self.function_name}","image":"{self.function_image}",'
            f'"timeoutMs":30000,"concurrency":4,"queueSize":100,"maxRetries":3,'
            f'"executionMode":"DEPLOYMENT",'
            f'"scalingConfig":{{"strategy":"INTERNAL","minReplicas":0,"maxReplicas":5,'
            f'"metrics":[{{"type":"in_flight","target":"2"}}]}}}}'
        )
        http_code = self._vm_exec(
            f"curl -sf -o /dev/null -w '%{{http_code}}' -X POST "
            f"'{nanofaas_url}/v1/functions' "
            f"-H 'Content-Type: application/json' "
            f"-d '{payload}'"
        )
        if http_code in ("200", "201"):
            self._pass(f"Function registered (HTTP {http_code})")
        else:
            self._fail(f"Function registration failed (HTTP {http_code})")
            return

        self._log("  Waiting for deployment to be created...")
        for _ in range(30):
            result = self._vm_exec(
                f"{self._kubeconfig_env_prefix()}kubectl get deployment {self.deploy_name} -n {self.namespace} "
                f">/dev/null 2>&1 && echo ok || echo missing"
            )
            if result == "ok":
                self._pass("Deployment created")
                return
            time.sleep(2)
        self._fail("Deployment not created after 60s")

    def _phase_b_baseline(self) -> None:
        self._log("")
        self._log("━━━ Phase B: Verify baseline replicas ━━━")

        self._log("  Waiting for deployment to settle (up to 60s)...")
        replicas = 0
        for _ in range(12):
            replicas = self._get_ready_replicas(self.deploy_name)
            self._log(f"    Ready replicas: {replicas}")
            if replicas >= 1:
                break
            time.sleep(5)

        desired = self._get_desired_replicas(self.deploy_name)
        self._log(f"  Baseline: desired={desired}, ready={replicas}")
        self._pass(f"Baseline recorded (desired={desired}, ready={replicas})")

    def _phase_c_load_and_scaleup(self, nanofaas_url: str) -> None:
        self._log("")
        self._log("━━━ Phase C: Apply load and verify scale-up ━━━")

        k6_script = self.repo_root / "experiments" / "k6" / "autoscaling.js"
        if not k6_script.exists():
            self._fail(f"k6 script not found: {k6_script}")
            return

        k6_log = Path("/tmp/k6-autoscaling.log")
        self._log("  Starting k6 load test in background...")
        with k6_log.open("w") as log_fh:
            k6_proc = subprocess.Popen(
                [
                    "k6",
                    "run",
                    "--env",
                    f"NANOFAAS_URL={nanofaas_url}",
                    "--env",
                    f"FUNCTION_NAME={self.function_name}",
                    str(k6_script),
                ],
                stdout=log_fh,
                stderr=log_fh,
            )
        self._log(f"  k6 PID: {k6_proc.pid}")

        max_replicas = 0
        scaled_up = False
        max_polls = 24  # 24 × 5s = 120s

        self._log("  Polling replicas every 5s for up to 120s...")
        for poll in range(1, max_polls + 1):
            time.sleep(5)
            replicas = self._get_ready_replicas(self.deploy_name)
            desired = self._get_desired_replicas(self.deploy_name)
            max_replicas = max(max_replicas, replicas, desired)
            self._log(
                f"    [{poll}/{max_polls}] desired={desired} ready={replicas} "
                f"max_seen={max_replicas}"
            )
            if max_replicas > 1:
                scaled_up = True
            if k6_proc.poll() is not None:
                self._log("  k6 finished")
                break

        if k6_proc.poll() is None:
            self._log("  Waiting for k6 to finish...")
            try:
                k6_proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                k6_proc.kill()

        self._max_replicas_observed = max_replicas
        if scaled_up:
            self._pass(f"Scale-up observed: max replicas = {max_replicas}")
        else:
            self._fail(f"Scale-up NOT observed: max replicas stayed at {max_replicas}")

    def _phase_d_scaledown(self) -> None:
        self._log("")
        self._log("━━━ Phase D: Wait for scale-down to zero ━━━")

        self._log("  Waiting 90s for scale-down cooldown (60s cooldown + 30s margin)...")
        time.sleep(90)

        max_polls = 24  # 24 × 5s = 120s
        self._log("  Polling replicas every 5s for up to 120s...")
        for poll in range(max_polls):
            desired = self._get_desired_replicas(self.deploy_name)
            self._log(f"    [{poll}/{max_polls}] desired={desired}")
            if desired == 0:
                self._pass("Scale-down to 0 verified (desired replicas = 0)")
                return
            time.sleep(5)

        final = self._get_desired_replicas(self.deploy_name)
        self._fail(f"Scale-down to 0 NOT observed: desired replicas = {final}")

    def _phase_e_report(self) -> None:
        final_desired = self._get_desired_replicas(self.deploy_name)
        final_ready = self._get_ready_replicas(self.deploy_name)

        self._log("")
        self._log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        self._log("         AUTOSCALING TEST REPORT")
        self._log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        self._log("")
        self._log(f"  Function:         {self.function_name}")
        self._log(f"  Max replicas:     {self._max_replicas_observed}")
        self._log(f"  Final desired:    {final_desired}")
        self._log(f"  Final ready:      {final_ready}")
        self._log("")
        self._log(f"  Passed: {self._pass_count}")
        self._log(f"  Failed: {self._fail_count}")
        self._log("")

        if self._fail_count > 0:
            self._log("ERROR: AUTOSCALING TEST FAILED")
            self._log("Control-plane logs (last 30 lines):")
            self._vm_exec(
                f"{self._kubeconfig_env_prefix()}kubectl logs -n {self.namespace} "
                f"-l app=nanofaas-control-plane --tail=30 2>/dev/null || true"
            )
            self._log("k6 output (last 20 lines):")
            k6_log = Path("/tmp/k6-autoscaling.log")
            if k6_log.exists():
                lines = k6_log.read_text(encoding="utf-8", errors="replace").splitlines()
                for line in lines[-20:]:
                    self._log(f"  {line}")
            sys.exit(1)
        else:
            self._log("AUTOSCALING TEST PASSED")


if __name__ == "__main__":
    AutoscalingExperiment().run()
