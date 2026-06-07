"""Shared loadgen sequence builders.

The loadgen body (K6Config + the install_k6/run_k6/fetch/prometheus/report task
sequence) is identical across the multipass/azure/proxmox loadtest scenarios; the
only differences are already-resolved inputs (endpoints, URLs, paths, runner). These
builders capture the shared shape so the sequence is defined once.

This module must not import controlplane_tool (import-linter contract): callers pass
already-resolved primitives in.
"""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from workflow_tasks.loadtest.models import K6Config, K6Stage, PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    RunK6,
    WriteK6Report,
)
from workflow_tasks.infra.ansible import install_k6_task
from workflow_tasks.shell import ShellBackend
from workflow_tasks.tasks.executors import VmCommandRunner


class RemotePaths(Protocol):
    script_path: str
    summary_path: str
    payload_path: str | None


def make_loadtest_k6_config(
    *,
    remote_paths: RemotePaths,
    control_plane_url: str,
    target_function: str,
    stages: Sequence[tuple[str, int]],
    vus: int | None,
    duration: str | None,
) -> K6Config:
    """Build the canonical loadtest K6Config from already-resolved inputs.

    ``remote_paths`` is duck-typed: it needs ``.script_path``, ``.summary_path`` and
    ``.payload_path`` (the result of ``two_vm_remote_paths``).
    """
    payload_path = remote_paths.payload_path
    return K6Config(
        script_path=Path(remote_paths.script_path),
        target_url=control_plane_url,
        summary_output_path=Path(remote_paths.summary_path),
        stages=tuple(K6Stage(duration=d, target=t) for d, t in stages),
        env={
            "NANOFAAS_URL": control_plane_url,
            "NANOFAAS_FUNCTION": target_function,
            **({"NANOFAAS_PAYLOAD": str(payload_path)} if payload_path else {}),
        },
        vus=vus,
        duration=duration,
        payload_path=Path(payload_path) if payload_path else None,
    )


@dataclass
class LoadgenBodyInputs:
    """Already-resolved inputs for the canonical loadgen body (5 tasks).

    ``task_ids``/``titles`` are 5-tuples in sequence order (install_k6, run_k6,
    fetch, prometheus, report) — passed in so per-lifecycle title suffixes are
    reproduced exactly.

    The ``install_*`` fields are forwarded to ``install_k6_task``:
    ``repo_root`` and ``shell`` control the local build environment; ``install_host``,
    ``install_user``, ``install_private_key``, and ``install_port`` identify the
    remote loadgen VM where k6 will be installed.
    """

    task_ids: tuple[str, str, str, str, str]
    titles: tuple[str, str, str, str, str]
    runner: VmCommandRunner
    fetcher: RemoteFileFetcher
    prometheus_client: PrometheusClient
    prometheus_queries: tuple[PrometheusQuery, ...]
    k6_config: K6Config
    remote_dir: str
    remote_summary_path: str
    run_dir: Path
    repo_root: Path
    shell: ShellBackend
    install_host: str
    install_user: str
    install_private_key: Path | None
    install_port: int | None = None


def build_loadgen_body_tasks(inputs: LoadgenBodyInputs) -> list:
    """Build the canonical 5-task loadgen body from resolved inputs.

    Returns [install_k6, run_k6, fetch, prometheus, report]. The prometheus window
    thunk reads the run_k6 task's result lazily (same as all three scenarios today).
    """
    install = install_k6_task(
        task_id=inputs.task_ids[0],
        title=inputs.titles[0],
        repo_root=inputs.repo_root,
        shell=inputs.shell,
        host=inputs.install_host,
        user=inputs.install_user,
        private_key=inputs.install_private_key,
        port=inputs.install_port,
    )
    run_k6 = RunK6(
        task_id=inputs.task_ids[1],
        title=inputs.titles[1],
        runner=inputs.runner,
        config=inputs.k6_config,
        remote_dir=inputs.remote_dir,
    )
    fetch = FetchVmResults(
        task_id=inputs.task_ids[2],
        title=inputs.titles[2],
        fetcher=inputs.fetcher,
        remote_source=inputs.remote_summary_path,
        local_dest=inputs.run_dir,
    )
    prometheus = CapturePrometheusSnapshot(
        task_id=inputs.task_ids[3],
        title=inputs.titles[3],
        client=inputs.prometheus_client,
        queries=inputs.prometheus_queries,
        window=lambda: TimeWindow(start=run_k6.result.started_at, end=run_k6.result.ended_at),
        output_dir=inputs.run_dir,
    )
    report = WriteK6Report(
        task_id=inputs.task_ids[4],
        title=inputs.titles[4],
        data_dir=inputs.run_dir,
        output_dir=inputs.run_dir,
    )
    return [install, run_k6, fetch, prometheus, report]
