from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import httpx
from tenacity import RetryError, Retrying, stop_after_delay, wait_fixed

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.gradle_planner import build_gradle_command
from controlplane_tool.process_streaming import spawn_logged_process
from controlplane_tool.tool_settings import ToolSettings


@dataclass
class ControlPlaneSession:
    base_url: str
    management_url: str
    api_port: int
    management_port: int
    owned_process: subprocess.Popen[str] | None = None
    log_path: Path | None = None

    @property
    def prometheus_scrape_target(self) -> str:
        return f"host.docker.internal:{self.management_port}"


class ControlPlaneRuntimeManager:
    def __init__(
        self,
        repo_root: Path,
        startup_timeout_seconds: float = 90.0,
    ) -> None:
        self.repo_root = repo_root
        self.startup_timeout_seconds = startup_timeout_seconds

    def ensure_available(self, run_dir: Path, kubernetes_api_url: str) -> ControlPlaneSession:
        settings = ToolSettings()
        external_url = settings.nanofaas_tool_control_plane_url.strip()
        if external_url:
            base_url = external_url.rstrip("/")
            management_url = settings.nanofaas_tool_control_plane_management_url.strip()
            if not management_url:
                management_url = "http://127.0.0.1:8081"
            if not self._wait_ready(base_url):
                raise RuntimeError(
                    f"configured control-plane URL not reachable: {base_url}/v1/functions"
                )
            return ControlPlaneSession(
                base_url=base_url,
                management_url=management_url.rstrip("/"),
                api_port=self._parse_port_or_default(base_url, default_port=8080),
                management_port=self._parse_port_or_default(management_url, default_port=8081),
                owned_process=None,
                log_path=None,
            )

        api_port = self._pick_local_port(preferred=8080)
        management_port = self._pick_local_port(preferred=8081, blocked_ports={api_port})
        base_url = f"http://127.0.0.1:{api_port}"
        management_url = f"http://127.0.0.1:{management_port}"
        log_path = run_dir / "control-plane-runtime.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)

        env = os.environ.copy()
        env.update(
            {
                "SERVER_PORT": str(api_port),
                "MANAGEMENT_SERVER_PORT": str(management_port),
                "KUBERNETES_MASTER": kubernetes_api_url.rstrip("/"),
                "KUBERNETES_TRUST_CERTIFICATES": "true",
                "KUBERNETES_AUTH_TRYKUBECONFIG": "false",
                "KUBERNETES_AUTH_TRYSERVICEACCOUNT": "false",
                "KUBERNETES_NAMESPACE": "default",
                "NANOFAAS_K8S_NAMESPACE": "default",
            }
        )

        command = build_gradle_command(
            repo_root=self.repo_root,
            request=BuildRequest(action="run", profile="k8s"),
            extra_gradle_args=["--console=plain"],
        )
        process = spawn_logged_process(
            command,
            cwd=self.repo_root,
            env=env,
            log_path=log_path,
        )

        if not self._wait_ready(base_url, process=process):
            self.cleanup(
                ControlPlaneSession(
                    base_url=base_url,
                    management_url=management_url,
                    api_port=api_port,
                    management_port=management_port,
                    owned_process=process,
                    log_path=log_path,
                )
            )
            tail = self._tail(log_path)
            raise RuntimeError(
                "control-plane runtime did not become ready in time on "
                f"{base_url}/v1/functions: {tail}"
            )

        return ControlPlaneSession(
            base_url=base_url,
            management_url=management_url,
            api_port=api_port,
            management_port=management_port,
            owned_process=process,
            log_path=log_path,
        )

    def cleanup(self, session: ControlPlaneSession) -> None:
        process = session.owned_process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def _wait_ready(
        self,
        base_url: str,
        process: subprocess.Popen[str] | None = None,
    ) -> bool:
        try:
            for attempt in Retrying(
                stop=stop_after_delay(self.startup_timeout_seconds),
                wait=wait_fixed(0.5),
            ):
                with attempt:
                    if process is not None and process.poll() is not None:
                        return False
                    if not self._is_ready(base_url):
                        raise RuntimeError("not ready")
        except RetryError:
            return False
        return True

    def _is_ready(self, base_url: str) -> bool:
        health_url = f"{base_url.rstrip('/')}/v1/functions"
        try:
            return httpx.get(health_url, timeout=2.0).status_code == 200
        except (httpx.RequestError, httpx.HTTPStatusError):
            return False

    def _pick_local_port(self, preferred: int, blocked_ports: set[int] | None = None) -> int:
        from controlplane_tool.net_utils import pick_local_port
        return pick_local_port(preferred=preferred, blocked=blocked_ports)

    def _tail(self, log_path: Path, max_chars: int = 400) -> str:
        if not log_path.exists():
            return "no logs"
        raw = log_path.read_text(encoding="utf-8", errors="replace").strip()
        if not raw:
            return "empty logs"
        if len(raw) <= max_chars:
            return raw
        return raw[-max_chars:]

    def _parse_port_or_default(self, url: str, default_port: int) -> int:
        value = url.strip().rstrip("/")
        if ":" not in value.rsplit("/", maxsplit=1)[-1]:
            return default_port
        try:
            return int(value.rsplit(":", maxsplit=1)[-1])
        except ValueError:
            return default_port
