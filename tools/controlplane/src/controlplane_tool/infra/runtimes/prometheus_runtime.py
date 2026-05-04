from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import time
import httpx
from tenacity import RetryError, Retrying, stop_after_delay, wait_fixed

from controlplane_tool.core.shell_backend import ShellExecutionResult, SubprocessShell
from controlplane_tool.app.settings import ToolSettings


@dataclass(frozen=True)
class PrometheusSession:
    url: str
    owned_container_name: str | None = None


class PrometheusRuntimeManager:
    def __init__(
        self,
        repo_root: Path,
        preferred_url: str | None = None,
        scrape_target: str = "host.docker.internal:8081",
        image: str = "prom/prometheus",
        docker_bin: str = "docker",
        startup_timeout_seconds: float = 25.0,
    ) -> None:
        self.repo_root = repo_root
        self.preferred_url = preferred_url
        self.scrape_target = scrape_target
        self.image = image
        self.docker_bin = docker_bin
        self.startup_timeout_seconds = startup_timeout_seconds

    def ensure_available(self, run_dir: Path) -> PrometheusSession:
        existing = self._discover_existing_url()
        if existing is not None:
            return PrometheusSession(url=existing, owned_container_name=None)

        self._ensure_image_available()
        config_path = self._write_config(run_dir).resolve()
        local_port = self._pick_local_port()
        container_name = f"controlplane-tool-prom-{int(time.time() * 1000)}"
        self._docker(
            [
                "run",
                "-d",
                "--rm",
                "--name",
                container_name,
                "--add-host=host.docker.internal:host-gateway",
                "-p",
                f"{local_port}:9090",
                "--mount",
                self._bind_mount_spec(
                    source=config_path,
                    target="/etc/prometheus/prometheus.yml",
                    readonly=True,
                ),
                self.image,
                "--config.file=/etc/prometheus/prometheus.yml",
                "--storage.tsdb.path=/prometheus",
            ],
            check=True,
        )

        url = f"http://127.0.0.1:{local_port}"
        if not self._wait_ready(url):
            self.cleanup(PrometheusSession(url=url, owned_container_name=container_name))
            raise RuntimeError("prometheus container did not become ready in time")
        return PrometheusSession(url=url, owned_container_name=container_name)

    def cleanup(self, session: PrometheusSession) -> None:
        if not session.owned_container_name:
            return
        self._docker(["rm", "-f", session.owned_container_name], check=False)

    def _discover_existing_url(self) -> str | None:
        for candidate in self._candidate_urls():
            if self._is_ready(candidate):
                return candidate
        return None

    def _candidate_urls(self) -> list[str]:
        candidates: list[str] = []
        if self.preferred_url and self.preferred_url.strip():
            candidates.append(self.preferred_url.strip())
        env_url = ToolSettings().nanofaas_tool_prometheus_url.strip()
        if env_url:
            candidates.append(env_url)
        candidates.append("http://127.0.0.1:9090")

        unique: list[str] = []
        for candidate in candidates:
            normalized = self._normalize_prometheus_base_url(candidate)
            if normalized is None:
                continue
            if normalized not in unique:
                unique.append(normalized)
        return unique

    def _normalize_prometheus_base_url(self, value: str) -> str | None:
        normalized = value.strip().rstrip("/")
        if not normalized:
            return None
        # Legacy profile values may still point to Spring's scrape endpoint.
        if normalized.endswith("/actuator/prometheus"):
            return None
        if normalized.endswith("/-/ready"):
            normalized = normalized[: -len("/-/ready")]
        if normalized.endswith("/api/v1"):
            normalized = normalized[: -len("/api/v1")]
        return normalized.rstrip("/")

    def _is_ready(self, base_url: str) -> bool:
        ready_url = self._ready_url(base_url)
        try:
            return httpx.get(ready_url, timeout=2.0).status_code == 200
        except (httpx.RequestError, httpx.HTTPStatusError):
            return False

    def _wait_ready(self, base_url: str) -> bool:
        try:
            for attempt in Retrying(
                stop=stop_after_delay(self.startup_timeout_seconds),
                wait=wait_fixed(0.5),
            ):
                with attempt:
                    if not self._is_ready(base_url):
                        raise RuntimeError("not ready")
        except RetryError:
            return False
        return True

    def _write_config(self, run_dir: Path) -> Path:
        config_dir = run_dir / "metrics" / "prometheus"
        config_dir.mkdir(parents=True, exist_ok=True)
        destination = config_dir / "prometheus.yml"
        destination.write_text(self._render_config(), encoding="utf-8")
        return destination

    def _bind_mount_spec(self, source: Path, target: str, readonly: bool) -> str:
        spec = f"type=bind,src={source},dst={target}"
        if readonly:
            spec = f"{spec},readonly"
        return spec

    def _pick_local_port(self) -> int:
        from controlplane_tool.core.net_utils import pick_local_port
        return pick_local_port(preferred=9090)

    def _docker(self, args: list[str], check: bool) -> ShellExecutionResult:
        completed = SubprocessShell().run(
            [self.docker_bin, *args],
            cwd=self.repo_root,
            dry_run=False,
        )
        if check and completed.return_code != 0:
            detail = completed.stderr.strip() or completed.stdout.strip() or "docker command failed"
            raise RuntimeError(f"{' '.join([self.docker_bin, *args])}: {detail}")
        return completed

    def _ensure_image_available(self) -> None:
        probe = self._docker(["image", "inspect", self.image], check=False)
        return_code = getattr(probe, "return_code", getattr(probe, "returncode", None))
        if return_code == 0:
            return
        self._docker(["pull", self.image], check=True)

    def _ready_url(self, base_url: str) -> str:
        return f"{base_url.rstrip('/')}/-/ready"

    def _render_config(self) -> str:
        return "\n".join(
            [
                "global:",
                "  scrape_interval: 1s",
                "  evaluation_interval: 1s",
                "",
                "scrape_configs:",
                "  - job_name: control-plane",
                "    metrics_path: /actuator/prometheus",
                "    static_configs:",
                f"      - targets: ['{self.scrape_target}']",
                "",
            ]
        )
