from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import sys
import httpx
from tenacity import RetryError, Retrying, stop_after_delay, wait_fixed

from controlplane_tool.core.process_streaming import spawn_logged_process
from controlplane_tool.app.settings import ToolSettings


@dataclass
class MockK8sSession:
    url: str
    owned_process: subprocess.Popen[str] | None = None
    log_path: Path | None = None


class MockK8sRuntimeManager:
    def __init__(
        self,
        repo_root: Path,
        preferred_url: str | None = None,
        startup_timeout_seconds: float = 15.0,
    ) -> None:
        self.repo_root = repo_root
        self.preferred_url = preferred_url
        self.startup_timeout_seconds = startup_timeout_seconds

    def ensure_available(self, run_dir: Path) -> MockK8sSession:
        existing = self._discover_existing_url()
        if existing is not None:
            return MockK8sSession(url=existing, owned_process=None, log_path=None)

        port = self._pick_local_port()
        log_path = run_dir / "mockk8s-runtime.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        src_root = Path(__file__).resolve().parents[3]
        existing_pythonpath = env.get("PYTHONPATH", "").strip()
        if existing_pythonpath:
            env["PYTHONPATH"] = f"{src_root}{os.pathsep}{existing_pythonpath}"
        else:
            env["PYTHONPATH"] = str(src_root)
        process = spawn_logged_process(
            [
                sys.executable,
                "-m",
                "controlplane_tool.infra.runtimes.mockk8s_server",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            cwd=self.repo_root,
            env=env,
            log_path=log_path,
        )

        url = f"http://127.0.0.1:{port}"
        if not self._wait_ready(url):
            self.cleanup(MockK8sSession(url=url, owned_process=process, log_path=log_path))
            tail = self._tail(log_path)
            raise RuntimeError(f"mock Kubernetes API did not become ready in time: {tail}")
        return MockK8sSession(url=url, owned_process=process, log_path=log_path)

    def cleanup(self, session: MockK8sSession) -> None:
        process = session.owned_process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def _discover_existing_url(self) -> str | None:
        for candidate in self._candidate_urls():
            if self._is_ready(candidate):
                return candidate
        return None

    def _candidate_urls(self) -> list[str]:
        candidates: list[str] = []
        if self.preferred_url and self.preferred_url.strip():
            candidates.append(self.preferred_url.strip())
        env_url = ToolSettings().nanofaas_tool_mockk8s_url.strip()
        if env_url:
            candidates.append(env_url)

        unique: list[str] = []
        for candidate in candidates:
            normalized = candidate.rstrip("/")
            if not normalized:
                continue
            if normalized not in unique:
                unique.append(normalized)
        return unique

    def _is_ready(self, base_url: str) -> bool:
        health_url = f"{base_url.rstrip('/')}/healthz"
        try:
            return httpx.get(health_url, timeout=1.5).status_code == 200
        except (httpx.RequestError, httpx.HTTPStatusError):
            return False

    def _wait_ready(self, base_url: str) -> bool:
        try:
            for attempt in Retrying(
                stop=stop_after_delay(self.startup_timeout_seconds),
                wait=wait_fixed(0.25),
            ):
                with attempt:
                    if not self._is_ready(base_url):
                        raise RuntimeError("not ready")
        except RetryError:
            return False
        return True

    def _pick_local_port(self) -> int:
        from controlplane_tool.core.net_utils import pick_local_port
        return pick_local_port(preferred=18080)

    def _tail(self, log_path: Path, max_chars: int = 320) -> str:
        if not log_path.exists():
            return "no logs"
        raw = log_path.read_text(encoding="utf-8", errors="replace").strip()
        if not raw:
            return "empty logs"
        if len(raw) <= max_chars:
            return raw
        return raw[-max_chars:]
