from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from workflow_tasks.loadtest.models import K6Config, K6RunResult
    from workflow_tasks.tasks.executors import VmCommandRunner


_K6_INSTALL_CMD: tuple[str, ...] = (
    "bash",
    "-lc",
    (
        "which k6 || ("
        "curl -fsSL https://pkg.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg"
        " && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main'"
        " | sudo tee /etc/apt/sources.list.d/k6.list"
        " && sudo apt-get update -qq && sudo apt-get install -y k6)"
    ),
)


def _build_k6_argv(config: "K6Config") -> tuple[str, ...]:
    args: list[str] = ["k6", "run", "--summary-export", str(config.summary_output_path)]
    if config.vus is not None:
        args.extend(["--vus", str(config.vus)])
    if config.duration is not None:
        args.extend(["--duration", config.duration])
    if config.vus is None and config.duration is None:
        for stage in config.stages:
            args.extend(["--stage", f"{stage.duration}:{stage.target}"])
    for key, value in config.env.items():
        args.extend(["-e", f"{key}={value}"])
    if config.payload_path is not None:
        args.extend(["-e", f"NANOFAAS_PAYLOAD={config.payload_path}"])
    args.append(str(config.script_path))
    return tuple(args)


@dataclass
class InstallK6:
    task_id: str
    title: str
    runner: "VmCommandRunner"
    remote_dir: str

    def run(self) -> None:
        result = self.runner.run_vm_command(
            _K6_INSTALL_CMD,
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"k6 install failed (exit {result.return_code})")


@dataclass
class RunK6:
    task_id: str
    title: str
    runner: "VmCommandRunner"
    config: "K6Config"
    remote_dir: str
    _result: "K6RunResult | None" = field(default=None, init=False, repr=False, compare=False)

    def run(self) -> "K6RunResult":
        from workflow_tasks.loadtest.models import K6RunResult

        started_at = datetime.now(timezone.utc)
        result = self.runner.run_vm_command(
            _build_k6_argv(self.config),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        ended_at = datetime.now(timezone.utc)
        self._result = K6RunResult(
            summary_path=self.config.summary_output_path,
            started_at=started_at,
            ended_at=ended_at,
            passed=result.return_code == 0,
        )
        return self._result

    @property
    def result(self) -> "K6RunResult":
        if self._result is None:
            raise RuntimeError("RunK6.run() has not been called")
        return self._result
