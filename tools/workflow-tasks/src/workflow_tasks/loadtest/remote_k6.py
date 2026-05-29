from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class RemoteK6RunConfig:
    script_path: Path
    summary_path: Path
    control_plane_url: str
    function_name: str
    payload_path: Path | None = None
    stages: tuple[tuple[str, int], ...] = ()
    custom_script: bool = False
    vus: int | None = None
    duration: str | None = None


def build_k6_command(config: RemoteK6RunConfig) -> tuple[str, ...]:
    args = [
        "k6",
        "run",
        "--summary-export",
        str(config.summary_path),
    ]

    if config.vus is not None:
        args.extend(["--vus", str(config.vus)])
    if config.duration is not None:
        args.extend(["--duration", config.duration])
    if not config.custom_script and config.vus is None and config.duration is None:
        for duration, target in config.stages:
            args.extend(["--stage", f"{duration}:{target}"])

    env = {
        "NANOFAAS_URL": config.control_plane_url,
        "NANOFAAS_FUNCTION": config.function_name,
    }
    if config.payload_path is not None:
        env["NANOFAAS_PAYLOAD"] = str(config.payload_path)

    for key, value in env.items():
        args.extend(["-e", f"{key}={value}"])
    args.append(str(config.script_path))
    return tuple(args)
