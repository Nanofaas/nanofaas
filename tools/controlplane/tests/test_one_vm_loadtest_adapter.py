from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.loadtest_flow import RunContext
from controlplane_tool.scenario.one_vm_loadtest_adapter import OneVmLoadtestAdapter
from workflow_tasks.components.function_tasks import RegisterFunctions
from workflow_tasks.loadtest.tasks import RunK6


@dataclass
class _Info:
    name: str = "nanofaas-e2e"
    host: str = "10.0.0.1"
    user: str = "ubuntu"
    home: str = "/home/ubuntu"


def _request(tmp_path: Path) -> E2eRequest:
    return E2eRequest(
        scenario="loadtest-one-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )


def _ctx() -> RunContext:
    return RunContext(
        stack_info=_Info(),
        stack_host="10.0.0.1",
        loadgen_info=_Info(),
        control_plane_url="http://10.0.0.1:30080",
        prometheus_url="http://10.0.0.1:30090",
        run_dir=Path("/tmp/run"),
        remote_paths=SimpleNamespace(
            root_dir="/home/ubuntu/two-vm-loadtest",
            scripts_dir="/home/ubuntu/two-vm-loadtest/scripts",
            payloads_dir="/home/ubuntu/two-vm-loadtest/payloads",
            results_dir="/home/ubuntu/two-vm-loadtest/results",
            script_path="/home/ubuntu/two-vm-loadtest/scripts/script.js",
            summary_path="/home/ubuntu/two-vm-loadtest/results/k6-summary.json",
            payload_path=None,
        ),
    )


def test_one_vm_adapter_reuses_stack_vm_for_loadgen(tmp_path: Path) -> None:
    adapter = OneVmLoadtestAdapter(
        runner=E2eRunner(repo_root=tmp_path),
        request=_request(tmp_path),
    )

    assert adapter.uses_dedicated_loadgen_vm() is False
    assert adapter.control_plane_url(_ctx()) == "http://10.0.0.1:30080"
    assert adapter.prometheus_url(_ctx()) == "http://10.0.0.1:30090"


def test_one_vm_adapter_builds_autoscaling_tail_tasks(tmp_path: Path) -> None:
    asset = tmp_path / "tools" / "controlplane" / "assets" / "k6" / "autoscaling.js"
    asset.parent.mkdir(parents=True)
    asset.write_text("export default function () {}\n", encoding="utf-8")
    adapter = OneVmLoadtestAdapter(
        runner=E2eRunner(repo_root=tmp_path),
        request=_request(tmp_path),
    )

    tasks = adapter.post_loadgen_tasks(_ctx())

    from controlplane_tool.autoscaling.tasks import (
        FetchAutoscalingSummary,
        RunK6WithReplicaWatch,
        VerifyAutoscalingReplicas,
    )

    assert [task.task_id for task in tasks] == [
        "autoscaling.register_function",
        "autoscaling.run_k6",
        "autoscaling.verify_replicas",
        "autoscaling.fetch_summary",
    ]
    assert isinstance(tasks[0], RegisterFunctions)
    assert isinstance(tasks[1], RunK6WithReplicaWatch)
    assert isinstance(tasks[1].run_k6, RunK6)
    assert tasks[1].run_k6.config.script_path == Path("/home/ubuntu/two-vm-loadtest/scripts/autoscaling.js")
    from controlplane_tool.scenario.scenario_helpers import selected_functions

    assert tasks[1].run_k6.config.env["NANOFAAS_FUNCTION"] == "echo-test"
    assert tasks[1].run_k6.config.env["NANOFAAS_FUNCTION"] in selected_functions(None)
    assert isinstance(tasks[2], VerifyAutoscalingReplicas)
    assert tasks[2].watcher is tasks[1].watcher
    # Must match where helm actually deploys (its None-namespace fallback);
    # a bare request used to leave this None and the kubectl probe failed.
    assert tasks[2].namespace == "nanofaas-e2e"
    assert isinstance(tasks[3], FetchAutoscalingSummary)
    assert tasks[3].local_path.name == "autoscaling-k6-summary.json"


def test_one_vm_adapter_endpoint_and_fetcher_are_constructible(tmp_path: Path) -> None:
    # Regression: these used lazy imports of non-existent names (_Endpoint,
    # _MultipassFetcher) that only blew up at runtime on a real VM.
    from controlplane_tool.scenario.loadtest_adapter import InstallEndpoint
    from workflow_tasks.vm.runners import VmFileFetcher

    adapter = OneVmLoadtestAdapter(
        runner=E2eRunner(repo_root=tmp_path),
        request=_request(tmp_path),
    )
    ctx = _ctx()

    endpoint = adapter.loadgen_install_endpoint(ctx)
    assert isinstance(endpoint, InstallEndpoint)
    assert endpoint.host == "10.0.0.1"
    assert endpoint.user == "ubuntu"

    fetcher = adapter.fetcher(ctx)
    assert isinstance(fetcher, VmFileFetcher)
