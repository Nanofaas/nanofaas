from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

from controlplane_tool.metrics import (
    build_required_metric_series,
    discover_control_plane_metric_names,
    missing_required_metrics,
    parse_prometheus_metric_names,
)
from controlplane_tool.mockk8s import default_mockk8s_test_selectors
from controlplane_tool.models import Profile


@dataclass(frozen=True)
class AdapterResult:
    ok: bool
    detail: str


class ShellCommandAdapter:
    def __init__(self, repo_root: Path | None = None) -> None:
        self.repo_root = repo_root or Path.cwd()

    def _modules_arg(self, profile: Profile) -> list[str]:
        if not profile.modules:
            return ["-PcontrolPlaneModules=none"]
        return [f"-PcontrolPlaneModules={','.join(profile.modules)}"]

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> AdapterResult:
        log_path = run_dir / log_name
        start = time.time()
        with log_path.open("a", encoding="utf-8") as log_file:
            log_file.write(f"$ {' '.join(command)}\n")
            completed = subprocess.run(
                command,
                cwd=self.repo_root,
                capture_output=True,
                text=True,
                check=False,
            )
            if completed.stdout:
                log_file.write(completed.stdout)
            if completed.stderr:
                log_file.write(completed.stderr)
        duration_ms = int((time.time() - start) * 1000)
        if completed.returncode == 0:
            return AdapterResult(ok=True, detail=f"ok ({duration_ms} ms)")
        return AdapterResult(
            ok=False,
            detail=f"exit={completed.returncode} ({duration_ms} ms), see {log_path.name}",
        )

    def _resolve_prometheus_url(self, profile: Profile) -> str | None:
        if profile.metrics.prometheus_url and profile.metrics.prometheus_url.strip():
            return profile.metrics.prometheus_url.strip()
        env_url = os.getenv("NANOFAAS_TOOL_PROMETHEUS_URL", "").strip()
        return env_url or None

    def _scrape_prometheus(self, url: str) -> tuple[bool, str]:
        try:
            with urlopen(url, timeout=2.5) as response:
                payload = response.read().decode("utf-8")
                return (True, payload)
        except (OSError, URLError) as exc:
            return (False, str(exc))

    def preflight(self, profile: Profile) -> list[str]:
        missing: list[str] = []
        if shutil.which("docker") is None:
            missing.append("docker")
        if profile.control_plane.implementation == "rust":
            if shutil.which("cargo") is None:
                missing.append("cargo")
        else:
            if not (self.repo_root / "gradlew").exists():
                missing.append("gradlew")
        if profile.tests.enabled and profile.tests.metrics and shutil.which("k6") is None:
            missing.append("k6")
        return missing

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            manifest = rust_dir / "Cargo.toml"
            if not manifest.exists():
                return (
                    False,
                    f"Rust control plane manifest not found at {manifest}",
                )
            result = self._run(
                ["cargo", "build", "--release", "--manifest-path", str(manifest)],
                run_dir,
                "build.log",
            )
            return (result.ok, result.detail)

        gradlew = str(self.repo_root / "gradlew")
        modules_arg = self._modules_arg(profile)
        if profile.control_plane.build_mode == "native":
            command = [gradlew, ":control-plane:nativeCompile", *modules_arg]
        else:
            command = [gradlew, ":control-plane:bootJar", *modules_arg]
        result = self._run(command, run_dir, "build.log")
        return (result.ok, result.detail)

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        tag = f"nanofaas/control-plane:{profile.name}"
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            dockerfile = rust_dir / "Dockerfile"
            if not dockerfile.exists():
                return (False, f"Rust Dockerfile not found at {dockerfile}")
            command = [
                "docker",
                "build",
                "-f",
                str(dockerfile),
                "-t",
                tag,
                str(rust_dir),
            ]
            result = self._run(command, run_dir, "build.log")
            return (result.ok, f"{result.detail}; image={tag}")

        if profile.control_plane.build_mode == "native":
            command = [
                str(self.repo_root / "gradlew"),
                ":control-plane:bootBuildImage",
                f"-PcontrolPlaneImage={tag}",
                *self._modules_arg(profile),
            ]
        else:
            command = [
                str(self.repo_root / "gradlew"),
                ":control-plane:bootJar",
                *self._modules_arg(profile),
            ]
            first = self._run(command, run_dir, "build.log")
            if not first.ok:
                return (False, first.detail)
            command = [
                "docker",
                "build",
                "-f",
                str(self.repo_root / "control-plane" / "Dockerfile"),
                "-t",
                tag,
                str(self.repo_root / "control-plane"),
            ]
        result = self._run(command, run_dir, "build.log")
        return (result.ok, f"{result.detail}; image={tag}")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = [
            str(self.repo_root / "gradlew"),
            ":control-plane:test",
            "--tests",
            "*ControlPlaneApiTest",
        ]
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = [str(self.repo_root / "gradlew"), ":control-plane:test"]
        for selector in default_mockk8s_test_selectors():
            command.extend(["--tests", selector])
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = [
            str(self.repo_root / "gradlew"),
            ":control-plane:test",
            "--tests",
            "*PrometheusEndpointTest",
            "--tests",
            "*MetricsTest",
        ]
        result = self._run(command, run_dir, "test.log")
        if not result.ok:
            return (False, result.detail)

        required_metrics = profile.metrics.required
        observed_metrics = discover_control_plane_metric_names(self.repo_root)
        snapshots: list[tuple[str, str]] = []
        scrape_errors: list[str] = []
        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)

        prometheus_url = self._resolve_prometheus_url(profile)
        if prometheus_url is not None:
            ok, payload = self._scrape_prometheus(prometheus_url)
            if ok:
                snapshots.append((datetime.now(timezone.utc).isoformat(), payload))
                observed_metrics.update(parse_prometheus_metric_names(payload))
            else:
                scrape_errors.append(payload)

        target_url = "http://localhost:8080"
        k6_script = self.repo_root / "experiments" / "k6" / "word-stats-java.js"
        k6_ok = True
        k6_detail = "k6 skipped"
        if k6_script.exists():
            k6_summary = metrics_dir / "k6-summary.json"
            k6_result = self._run(
                [
                    "k6",
                    "run",
                    "--summary-export",
                    str(k6_summary),
                    "-e",
                    f"NANOFAAS_URL={target_url}",
                    str(k6_script),
                ],
                run_dir,
                "test.log",
            )
            k6_ok = k6_result.ok
            k6_detail = k6_result.detail

            if prometheus_url is not None:
                ok, payload = self._scrape_prometheus(prometheus_url)
                if ok:
                    snapshots.append((datetime.now(timezone.utc).isoformat(), payload))
                    observed_metrics.update(parse_prometheus_metric_names(payload))
                else:
                    scrape_errors.append(payload)

        missing = missing_required_metrics(required_metrics, observed_metrics)
        if snapshots:
            series = build_required_metric_series(snapshots=snapshots, required=required_metrics)
        else:
            now = datetime.now(timezone.utc).isoformat()
            series = {
                metric: [{"timestamp": now, "value": 1.0}]
                for metric in sorted(observed_metrics)
            }

        (metrics_dir / "series.json").write_text(json.dumps(series, indent=2), encoding="utf-8")
        (metrics_dir / "observed-metrics.json").write_text(
            json.dumps(
                {
                    "source": "prometheus-scrape" if snapshots else "control-plane-catalog",
                    "observed": sorted(observed_metrics),
                    "required": required_metrics,
                    "missing": missing,
                    "scrape_errors": scrape_errors,
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        if missing:
            return (
                False,
                "missing required metrics: "
                + ", ".join(missing)
                + " (see metrics/observed-metrics.json)",
            )
        if not k6_ok:
            return (False, f"prometheus checks passed, k6 failed: {k6_detail}")
        if k6_script.exists():
            return (True, f"prometheus + k6: {k6_detail}")
        return (True, "prometheus checks passed; k6 script missing, skipped load")
