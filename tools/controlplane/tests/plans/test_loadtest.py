from dataclasses import dataclass, field
from pathlib import Path

import pytest

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.loadtest import build_loadtest_plan
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


class NoopPrometheus:
    def query_range(self, *args, **kwargs):
        return []


SCENARIO = ScenarioConfig(workflow="loadtest", functions=["word-stats-java"])


@pytest.mark.parametrize(
    ("environment", "expected_role", "fetches"),
    [
        (EnvironmentConfig(provider="local"), "stack", False),
        (
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            "stack",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "external",
                    "roles": {
                        "stack": {"host": "stack.example"},
                        "loadgen": {"host": "load.example"},
                    },
                }
            ),
            "loadgen",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "azure",
                    "azure": {"resource_group": "rg", "location": "westeurope"},
                    "roles": {"stack": {"name": "s"}, "loadgen": {"name": "l"}},
                }
            ),
            "loadgen",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "proxmox",
                    "proxmox": {"host": "pve", "node": "pve1"},
                    "roles": {"stack": {"name": "s"}, "loadgen": {"name": "l"}},
                }
            ),
            "loadgen",
            True,
        ),
    ],
)
def test_provider_contract_selects_role_and_result_transport(
    tmp_path: Path,
    environment: EnvironmentConfig,
    expected_role: str,
    fetches: bool,
) -> None:
    executor = RecordingExecutor()
    workflow = build_loadtest_plan(
        SCENARIO,
        environment,
        RoleBindings(host=executor, stack=executor, loadgen=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        fetcher=object() if fetches else None,
    )

    assert workflow.tasks[0].spec.role == expected_role
    assert ("loadgen.fetch_results" in workflow.task_ids) is fetches
