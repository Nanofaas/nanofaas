from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen


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
        external_url = os.getenv("NANOFAAS_TOOL_CONTROL_PLANE_URL", "").strip()
        if external_url:
            base_url = external_url.rstrip("/")
            management_url = os.getenv("NANOFAAS_TOOL_CONTROL_PLANE_MANAGEMENT_URL", "").strip()
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
        log_file = log_path.open("a", encoding="utf-8")

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

        process = subprocess.Popen(
            [str(self.repo_root / "gradlew"), ":control-plane:bootRun", "--console=plain"],
            cwd=self.repo_root,
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
        )
        log_file.close()

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
        start = time.time()
        while time.time() - start < self.startup_timeout_seconds:
            if process is not None and process.poll() is not None:
                return False
            if self._is_ready(base_url):
                return True
            time.sleep(0.5)
        return False

    def _is_ready(self, base_url: str) -> bool:
        health_url = f"{base_url.rstrip('/')}/v1/functions"
        try:
            with urlopen(health_url, timeout=2.0) as response:
                return int(getattr(response, "status", 0)) == 200
        except (OSError, URLError):
            return False

    def _pick_local_port(self, preferred: int, blocked_ports: set[int] | None = None) -> int:
        blocked = blocked_ports or set()
        if preferred not in blocked and self._is_port_free(preferred):
            return preferred
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            candidate = int(sock.getsockname()[1])
        if candidate in blocked:
            return self._pick_local_port(preferred=0, blocked_ports=blocked)
        return candidate

    def _is_port_free(self, port: int) -> bool:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("127.0.0.1", port))
            except OSError:
                return False
        return True

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
