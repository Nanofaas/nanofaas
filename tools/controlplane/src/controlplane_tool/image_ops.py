from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.runtime_primitives import PlannedCommand


@dataclass(slots=True)
class ImageOps:
    repo_root: Path
    runtime: str = "docker"

    def build(
        self,
        *,
        image: str,
        context: Path,
        dockerfile: Path | None = None,
        build_args: dict[str, str] | None = None,
    ) -> PlannedCommand:
        command = [self.runtime, "build"]
        if dockerfile is not None:
            command.extend(["-f", str(dockerfile)])
        command.extend(["-t", image])
        for key, value in sorted((build_args or {}).items()):
            command.extend(["--build-arg", f"{key}={value}"])
        command.append(str(context))
        return PlannedCommand(command=command, cwd=Path(self.repo_root))

    def push(self, image: str) -> PlannedCommand:
        return PlannedCommand(command=[self.runtime, "push", image], cwd=Path(self.repo_root))

    def tag(self, source: str, target: str) -> PlannedCommand:
        return PlannedCommand(command=[self.runtime, "tag", source, target], cwd=Path(self.repo_root))
