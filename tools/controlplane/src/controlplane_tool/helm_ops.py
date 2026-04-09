from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.runtime_primitives import PlannedCommand


@dataclass(slots=True)
class HelmOps:
    repo_root: Path
    binary: str = "helm"

    def upgrade_install(
        self,
        *,
        release: str,
        chart: Path,
        namespace: str,
        values: dict[str, str] | None = None,
        wait: bool = True,
        timeout: str = "3m",
        dry_run: bool = False,
    ) -> PlannedCommand:
        command = [
            self.binary,
            "upgrade",
            "--install",
            release,
            str(chart),
            "-n",
            namespace,
        ]
        for key, value in sorted((values or {}).items()):
            command.extend(["--set", f"{key}={value}"])
        if wait:
            command.append("--wait")
        if timeout:
            command.extend(["--timeout", timeout])
        if dry_run:
            command.append("--dry-run")
        return PlannedCommand(command=command, cwd=Path(self.repo_root))
