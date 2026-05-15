"""
grafana_runtime.py

Grafana Docker runtime management for local loadtest visualization (M12).

Replaces the Grafana startup/teardown logic in experiments/e2e-loadtest.sh.

Usage:
    from controlplane_tool.grafana_runtime import GrafanaRuntime

    grafana = GrafanaRuntime(repo_root, prom_url="http://192.168.64.2:30090")
    if grafana.is_docker_available():
        grafana.start()
        # ... run loadtests ...
        grafana.stop()
"""
from __future__ import annotations

from workflow_tasks import step, skip

import os
import shutil
import subprocess
from pathlib import Path


class GrafanaRuntime:
    """Manage a local Grafana Docker Compose instance for loadtest dashboards.

    Wraps the docker-compose workflow previously embedded in experiments/e2e-loadtest.sh.
    """

    def __init__(
        self,
        repo_root: Path,
        *,
        prom_url: str = "http://localhost:30090",
    ) -> None:
        self.repo_root = Path(repo_root)
        self.prom_url = prom_url
        self._compose_file = self.repo_root / "experiments" / "grafana" / "docker-compose.yml"

    def is_docker_available(self) -> bool:
        return shutil.which("docker") is not None

    def is_compose_file_available(self) -> bool:
        return self._compose_file.exists()

    def start(self) -> None:
        """Start the Grafana Docker Compose stack."""
        if not self.is_docker_available():
            skip("docker not found, skipping Grafana startup")
            return
        if not self.is_compose_file_available():
            skip("compose file not found: {self._compose_file}, skipping")
            return
        step(f"Starting Grafana stack (PROM_URL={self.prom_url})")
        subprocess.run(
            self._compose_command("up", "-d"),
            check=True,
            env={"PROM_URL": self.prom_url, **os.environ},
        )

    def stop(self) -> None:
        """Stop the Grafana Docker Compose stack."""
        if not self.is_docker_available() or not self.is_compose_file_available():
            return
        step("Stopping Grafana stack")
        subprocess.run(
            self._compose_command("down"),
            check=False,
        )

    def _compose_command(self, *args: str) -> list[str]:
        return ["docker", "compose", "-f", str(self._compose_file), *args]
