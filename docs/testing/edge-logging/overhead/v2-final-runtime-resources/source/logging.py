"""Optional node collectors owned by existing Sonata compensation/lifetimes."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from sonata_engine import Resource, TaskInputs
from sonata_tasks.compensation import compensated_resource

from nanolab.config.one_shot import OneShotLogs
from nanolab.one_shot.infrastructure import NodeResource


def query_url(endpoint: str) -> str:
    """Derive the query API beside the configured push endpoint."""
    return endpoint.removesuffix("/push") + "/query_range"


def backend_resource(logs: OneShotLogs, *, root: Path, run_dir: Path) -> Resource[str]:
    """Preserve external endpoints and own local backends only when requested."""
    endpoint_url = logs.endpoint
    if not endpoint_url:
        raise ValueError("logging backend endpoint is required")
    if not logs.backend_owned:
        return Resource(
            title="Use external logging backend",
            acquire=lambda _: endpoint_url,
            release=lambda _inputs, _value: None,
        )
    project = "nanolab-logs-" + uuid4().hex[:12]
    endpoint = urlsplit(endpoint_url)
    environment = dict(
        os.environ,
        LOKI_BIND=logs.backend_bind,
        LOKI_LISTEN_PORT=str(endpoint.port or 80),
        GRAFANA_PORT="0",
    )
    argv = [
        "docker",
        "compose",
        "-p",
        project,
        "-f",
        str(root / "deploy/observability/compose.yaml"),
        "--profile",
        "backend",
    ]

    def command(*args, check=True):
        return subprocess.run(
            argv + list(args),
            env=environment,
            capture_output=True,
            text=True,
            check=check,
            timeout=90,
        )

    def release(_inputs, _value=None):
        directory = run_dir / "logging"
        try:
            directory.mkdir(parents=True, exist_ok=True)
            captured = command("logs", "--no-color", check=False)
            (directory / "backend.log").write_text(captured.stdout + captured.stderr)
        except Exception as error:
            logging.getLogger(__name__).warning("Backend log export failed: %s", error)
        result = command("down", "-v", "--remove-orphans", check=False)
        try:
            (directory / "backend-cleanup.log").write_text(
                result.stdout + result.stderr
            )
        except OSError as error:
            logging.getLogger(__name__).warning(
                "Backend cleanup receipt failed: %s", error
            )
        remaining = subprocess.check_output(
            [
                "docker",
                "ps",
                "-aq",
                "--filter",
                "label=com.docker.compose.project=" + project,
            ],
            text=True,
        ).strip()
        volumes = subprocess.check_output(
            [
                "docker",
                "volume",
                "ls",
                "-q",
                "--filter",
                "label=com.docker.compose.project=" + project,
            ],
            text=True,
        ).strip()
        if result.returncode or remaining or volumes:
            raise RuntimeError("owned logging backend cleanup unconfirmed")

    def acquire(_inputs):
        command("up", "-d")
        deadline = time.monotonic() + 90
        with httpx.Client(trust_env=False) as http:
            while time.monotonic() < deadline:
                try:
                    http.get(
                        endpoint_url.split("/loki/api/v1/push")[0] + "/ready",
                        timeout=2,
                    ).raise_for_status()
                    return endpoint_url
                except httpx.HTTPError:
                    time.sleep(0.5)
        raise RuntimeError("owned logging backend readiness failed")

    return compensated_resource(
        title="Own local Loki/Grafana logging backend",
        acquire=acquire,
        release=release,
        compensate=release,
    )


@dataclass(frozen=True)
class LogSession:
    """Retain the measured collector identity and export interval."""

    node_id: str
    container: str
    volume: str
    started_ns: int
    identity: dict


def collection_evidence(metrics: str) -> dict:
    """Conservatively distinguish delivery counters from query transport success."""
    totals: dict[str, float] = {}
    for line in metrics.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        parts = line.split()
        key = parts[0].split("{", 1)[0]
        try:
            totals[key] = totals.get(key, 0) + float(parts[1])
        except (ValueError, IndexError):
            continue
    required = [
        "fluentbit_input_records_total",
        "fluentbit_output_proc_records_total",
        "fluentbit_output_dropped_records_total",
    ]
    if any(key not in totals for key in required):
        return {"status": "unverified", "reason": "delivery counters unavailable"}
    received, delivered, dropped = (totals[key] for key in required)
    dropped += sum(
        value
        for key, value in totals.items()
        if key
        in {
            "fluentbit_filter_drop_records_total",
            "fluentbit_input_long_line_skipped_total",
            "fluentbit_input_long_line_truncated_total",
            "fluentbit_input_multiline_truncated_total",
        }
    )
    pending = max(0, received - delivered - dropped)
    return {
        "status": "loss" if dropped else "pending" if pending else "drained",
        "received": received,
        "delivered": delivered,
        "dropped": dropped,
        "pending": pending,
    }


def collector_resource(
    node: NodeResource,
    logs: OneShotLogs,
    *,
    bootstrap: Resource,
    backend: Resource[str],
    root: Path,
    run_dir: Path,
    mode: str,
) -> Resource[LogSession]:
    """Own persistent buffering and export independently of SDK accounting."""
    token = uuid4().hex[:12]
    name = "nanolab-logs-" + node.config.id + "-" + token
    volume = name + "-state"
    assets = {
        filename: root / "deploy/observability" / filename
        for filename in ["fluent-bit.conf", "parsers.conf"]
    }
    hashes = {
        key: hashlib.sha256(path.read_bytes()).hexdigest()
        for key, path in assets.items()
    }
    created = False
    session = None
    final_metrics = ""
    archive_issue = None

    def metrics_snapshot():
        return node.command(
            (
                "python3",
                "-c",
                "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:2020/api/v1/metrics/prometheus',timeout=3).read().decode())",
            )
        ).stdout

    def archive_state():
        directory = run_dir / "logging" / node.config.id
        directory.mkdir(parents=True, exist_ok=True)
        mountpoint = node.command(
            ("docker", "volume", "inspect", "--format={{.Mountpoint}}", volume)
        ).stdout.strip()
        if not mountpoint.startswith("/"):
            raise RuntimeError("collector state mountpoint unavailable")
        private = node.command(("mktemp", "-d")).stdout.strip()
        if not private.startswith("/"):
            raise RuntimeError("collector archive private directory unavailable")
        remote = private + "/state.tar.gz"
        node.command(("sudo", "tar", "-czf", remote, "-C", mountpoint, "."))
        node.command(("sudo", "chmod", "644", remote))
        archive = directory / "collector-state.tar.gz"
        copied = node.provider.transfer_from(
            node.request, source=remote, destination=archive
        )
        if copied.return_code or not archive.is_file() or not archive.stat().st_size:
            raise RuntimeError("collector state archive transfer failed")
        remote_hash = node.command(("sha256sum", remote)).stdout.split()[0]
        if hashlib.sha256(archive.read_bytes()).hexdigest() != remote_hash:
            raise RuntimeError("collector state archive checksum mismatch")
        node.command(("rm", "-f", remote))
        node.command(("rmdir", private))

    def export():
        directory = run_dir / "logging" / node.config.id
        directory.mkdir(parents=True, exist_ok=True)
        status = {
            "status": "complete",
            "exportStatus": "complete",
            "nodeId": node.config.id,
            "issues": [],
            "truncated": False,
            "collection": collection_evidence(final_metrics),
            "stateArchive": "verified" if archive_issue is None else "failed",
        }
        if status["collection"]["status"] != "drained":
            status["issues"].append(
                "collection delivery is " + status["collection"]["status"]
            )
        if archive_issue:
            status["issues"].append(
                "recoverable state archive failed: " + archive_issue
            )
        try:
            (directory / "collector-metrics.txt").write_text(final_metrics)
            stats = node.command(
                ("docker", "stats", "--no-stream", "--format={{json .}}", name)
            )
            (directory / "collector-stats.json").write_text(stats.stdout)
            observed = node.command(("docker", "logs", name))
            (directory / "collector.log").write_text(observed.stdout + observed.stderr)
        except Exception as error:
            status["issues"].append(str(error))
        try:
            if session is None or logs.endpoint is None:
                raise RuntimeError(
                    "collector identity or endpoint unavailable during export"
                )
            params = {
                "query": '{job="nanofaas",node='
                + json.dumps(node.config.id)
                + "} | json | runId="
                + json.dumps(run_dir.name),
                "start": str(session.started_ns),
                "end": str(time.time_ns()),
                "limit": str(logs.export_limit),
            }
            with httpx.Client(trust_env=False) as http:
                response = http.get(query_url(logs.endpoint), params=params, timeout=10)
                response.raise_for_status()
                result = response.json()
            (directory / "loki-query.json").write_text(
                json.dumps(result, indent=2) + "\n"
            )
            count = sum(len(stream["values"]) for stream in result["data"]["result"])
            status["truncated"] = count >= logs.export_limit
            if status["truncated"]:
                status["issues"].append("Loki export limit reached; query is partial")
                status["exportStatus"] = "partial"
            if count < status["collection"].get("delivered", 0):
                status["issues"].append(
                    "query contains fewer records than collector delivery counter"
                )
                status["exportStatus"] = "partial"
        except Exception as error:
            status["issues"].append(str(error))
            status["exportStatus"] = "partial"
        if status["issues"]:
            status["status"] = "partial"
        (directory / "status.json").write_text(json.dumps(status, indent=2) + "\n")
        (directory / "sha256.json").write_text(
            json.dumps(
                {
                    p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in directory.iterdir()
                    if p.is_file() and p.name != "sha256.json"
                },
                indent=2,
            )
            + "\n"
        )

    def release(_inputs, _value=None):
        nonlocal created, final_metrics, archive_issue
        if not created:
            return
        # The platform/function resources depend on this collector: they stop first.
        # Bound tail/flush/retry, then stop before archiving SQLite/chunks.
        for _ in range(5):
            time.sleep(2)
            try:
                final_metrics = metrics_snapshot()
                if collection_evidence(final_metrics)["status"] == "drained":
                    break
            except Exception:
                break
        stopped = node.provider.exec_argv(
            node.request, ("docker", "stop", "-t", "15", name)
        )
        try:
            if stopped.return_code:
                raise RuntimeError("collector graceful stop failed")
            archive_state()
        except Exception as error:
            archive_issue = str(error)
            logging.getLogger(__name__).error(
                "Recoverable collector state archive failed for %s: %s",
                node.config.id,
                error,
            )
        try:
            export()
        except Exception as error:
            logging.getLogger(__name__).warning(
                "Log export failed for %s: %s", node.config.id, error
            )
        removed = node.provider.exec_argv(node.request, ("docker", "rm", "-f", name))
        if (
            removed.return_code
            and "No such container" not in removed.stdout + removed.stderr
        ):
            raise RuntimeError("collector cleanup unconfirmed")
        deleted = node.provider.exec_argv(
            node.request, ("docker", "volume", "rm", volume)
        )
        if (
            deleted.return_code
            and "no such volume" not in (deleted.stdout + deleted.stderr).lower()
        ):
            raise RuntimeError("collector volume cleanup unconfirmed")
        created = False
        if archive_issue:
            raise RuntimeError(
                "collector state archive failed before owned cleanup: " + archive_issue
            )

    def acquire(inputs: TaskInputs):
        nonlocal created, session
        vm = inputs.resource(node.vm)
        endpoint = urlsplit(inputs.resource(backend))
        remote = vm.home + "/nanolab-logging-" + token
        node.command(("mkdir", "-p", remote))
        for filename, path in assets.items():
            staged = node.provider.transfer_to(
                node.request, source=path, destination=remote + "/" + filename
            )
            if staged.return_code:
                raise RuntimeError("collector config transfer failed")
        verified = node.command(("sha256sum", *(remote + "/" + key for key in assets)))
        if [line.split()[0] for line in verified.stdout.splitlines()] != list(
            hashes.values()
        ):
            raise ValueError("collector config hash mismatch")
        driver = node.command(
            ("docker", "info", "--format={{.LoggingDriver}}")
        ).stdout.strip()
        if driver != "json-file":
            raise ValueError(
                "managed function collection requires Docker json-file driver"
            )
        root_dir = node.command(
            ("docker", "info", "--format={{.DockerRootDir}}")
        ).stdout.strip()
        if not root_dir.startswith("/"):
            raise ValueError("Docker log root unavailable")
        existing = node.provider.exec_argv(
            node.request, ("docker", "volume", "inspect", volume)
        )
        if not existing.return_code:
            raise RuntimeError("refuse pre-existing collector state volume")
        node.command(
            (
                "docker",
                "volume",
                "create",
                "--label",
                "nanolab.logging=" + token,
                volume,
            )
        )
        created = True
        values = {
            "NANOFAAS_NODE_ID": node.config.id,
            "NANOFAAS_ENVIRONMENT": logs.environment,
            "NANOFAAS_CAMPAIGN_ID": logs.campaign_id,
            "NANOFAAS_RUN_ID": run_dir.name,
            "NANOFAAS_MODE": mode,
            "NANOFAAS_NODE_ROLE": node.config.kind,
            "NANOFAAS_LOG_BUFFER_SIZE": str(logs.buffer_mib) + "M",
            "LOKI_HOST": endpoint.hostname,
            "LOKI_PORT": str(
                endpoint.port or (443 if endpoint.scheme == "https" else 80)
            ),
            "LOKI_URI": endpoint.path,
            "LOKI_TLS": "On" if endpoint.scheme == "https" else "Off",
        }
        argv = [
            "docker",
            "run",
            "-d",
            "--name",
            name,
            "--network",
            "host",
            "--read-only",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--cpus",
            str(logs.cpu),
            "--memory",
            str(logs.memory_mib) + "m",
            "--log-driver",
            "local",
            "--log-opt",
            "max-size=10m",
            "--log-opt",
            "max-file=2",
            "-v",
            root_dir + "/containers:/var/log/containers:ro",
            "-v",
            volume + ":/var/lib/fluent-bit",
            "-v",
            remote + "/fluent-bit.conf:/fluent-bit/etc/fluent-bit.conf:ro",
            "-v",
            remote + "/parsers.conf:/fluent-bit/etc/parsers.conf:ro",
        ]
        for key, value in values.items():
            argv += ["-e", key + "=" + str(value)]
        started = time.time_ns()
        node.command((*argv, logs.image))
        observed = json.loads(node.command(("docker", "inspect", name)).stdout)[0]
        if not observed["State"]["Running"]:
            raise RuntimeError("owned collector is not running")
        budget = observed["HostConfig"]
        if (
            budget["NanoCpus"] != round(logs.cpu * 1e9)
            or budget["Memory"] != logs.memory_mib * 1024 * 1024
        ):
            raise ValueError("collector resource budget mismatch")
        session = LogSession(
            node.config.id,
            name,
            volume,
            started,
            {
                "image": observed["Image"],
                "configSha256": hashes,
                "cpu": logs.cpu,
                "memoryMiB": logs.memory_mib,
                "bufferMiB": logs.buffer_mib,
            },
        )
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = node.provider.exec_argv(
                node.request,
                (
                    "python3",
                    "-c",
                    "import urllib.request;urllib.request.urlopen('http://127.0.0.1:2020/api/v1/metrics/prometheus',timeout=2).read()",
                ),
            )
            if not result.return_code:
                return session
            time.sleep(0.5)
        raise RuntimeError("collector readiness failed")

    return compensated_resource(
        title="Own " + node.config.id + " Fluent Bit collector",
        acquire=acquire,
        release=release,
        compensate=release,
        requires=(node.vm, bootstrap, backend),
    )
