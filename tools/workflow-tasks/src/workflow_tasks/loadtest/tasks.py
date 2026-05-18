from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks.loadtest.models import PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher

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


@dataclass
class FetchVmResults:
    task_id: str
    title: str
    fetcher: RemoteFileFetcher
    remote_source: str
    local_dest: Path

    def run(self) -> Path:
        self.local_dest.mkdir(parents=True, exist_ok=True)
        self.fetcher.fetch_from(self.remote_source, self.local_dest)
        return self.local_dest


@dataclass
class CapturePrometheusSnapshot:
    task_id: str
    title: str
    client: PrometheusClient
    queries: tuple[PrometheusQuery, ...]
    window: TimeWindow | Callable[[], TimeWindow]
    output_dir: Path

    def _resolve_window(self) -> TimeWindow:
        if callable(self.window):
            return self.window()
        return self.window

    def run(self) -> Path:
        window = self._resolve_window()
        metrics_dir = self.output_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)

        result: dict[str, dict] = {}
        for q in self.queries:
            entry: dict[str, object] = {"query": q.expr, "required": q.required, "points": []}
            try:
                points = self.client.query_range(q.expr, window)
            except RuntimeError as exc:
                if q.required:
                    raise RuntimeError(f"required query '{q.name}' failed: {exc}") from exc
                entry["error"] = str(exc)
                result[q.name] = entry
                continue
            if q.required and not points:
                raise RuntimeError(f"required query '{q.name}' returned no data")
            entry["points"] = points
            result[q.name] = entry

        snapshot = {
            "start": window.start.isoformat(),
            "end": window.end.isoformat(),
            "queries": result,
        }
        dest = metrics_dir / "prometheus-snapshot.json"
        dest.write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
        return dest


def _render_k6_html(k6_summary: dict, prom_snapshot: dict | None) -> str:
    metrics = k6_summary.get("metrics", {})
    rows: list[str] = []
    for name, entry in metrics.items():
        if not isinstance(entry, dict):
            continue
        values = entry.get("values", {})
        formatted = " | ".join(
            f"{k}: {v:.3g}" if isinstance(v, float) else f"{k}: {v}"
            for k, v in values.items()
        )
        rows.append(f"<tr><td>{name}</td><td>{entry.get('type', '')}</td><td>{formatted}</td></tr>")

    prom_section = ""
    if prom_snapshot:
        queries = prom_snapshot.get("queries", {})
        prom_rows = [
            f"<tr><td>{metric}</td><td>{len(data.get('points', []))} points</td></tr>"
            for metric, data in queries.items()
            if isinstance(data, dict)
        ]
        if prom_rows:
            prom_section = (
                "<h2>Prometheus Metrics</h2>"
                "<table><tr><th>Metric</th><th>Data</th></tr>"
                + "".join(prom_rows)
                + "</table>"
            )

    return (
        "<!DOCTYPE html>\n"
        '<html lang="en">\n'
        "<head>\n"
        '<meta charset="utf-8">\n'
        "<title>k6 Loadtest Report</title>\n"
        "<style>\n"
        "  body { font-family: sans-serif; max-width: 1000px; margin: 0 auto; padding: 24px; }\n"
        "  h1, h2 { border-bottom: 1px solid #eee; padding-bottom: 8px; }\n"
        "  table { border-collapse: collapse; width: 100%; margin: 16px 0; }\n"
        "  th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n"
        "  th { background: #f5f5f5; font-weight: 600; }\n"
        "  tr:hover { background: #fafafa; }\n"
        "</style>\n"
        "</head>\n"
        "<body>\n"
        "<h1>k6 Loadtest Report</h1>\n"
        "<h2>k6 Metrics</h2>\n"
        "<table>\n"
        "<tr><th>Metric</th><th>Type</th><th>Values</th></tr>\n"
        + "".join(rows)
        + "\n</table>\n"
        + prom_section
        + "\n</body>\n</html>"
    )


@dataclass
class WriteK6Report:
    task_id: str
    title: str
    data_dir: Path
    output_dir: Path

    def run(self) -> Path:
        k6_summary_path = self.data_dir / "k6-summary.json"
        k6_summary = json.loads(k6_summary_path.read_text(encoding="utf-8"))

        prom_path = self.data_dir / "metrics" / "prometheus-snapshot.json"
        prom_snapshot: dict | None = None
        if prom_path.exists():
            try:
                prom_snapshot = json.loads(prom_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass

        self.output_dir.mkdir(parents=True, exist_ok=True)
        html = _render_k6_html(k6_summary, prom_snapshot)
        dest = self.output_dir / "report.html"
        dest.write_text(html, encoding="utf-8")
        return dest
