from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from typer.testing import CliRunner
from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import TaskResult
from workflow_tasks.vm.models import VmRequest

from controlplane_tool.app.main import app
import controlplane_tool.cli.images as images_module
from controlplane_tool.config import EnvironmentConfig
from controlplane_tool.images.plan import build_image_plan
from controlplane_tool.workspace.paths import default_tool_paths


def test_images_command_surface_contains_only_plan_and_build() -> None:
    result = CliRunner().invoke(app, ["images", "--help"])

    assert result.exit_code == 0
    image_commands = {
        command.name
        for group in app.registered_groups
        if group.name == "images"
        for command in group.typer_instance.registered_commands
    }
    assert image_commands == {
        "plan",
        "build",
    }
    assert "publish" not in result.output


def test_images_plan_writes_bake_json_and_renders_bake_and_native_tasks(
    tmp_path: Path,
) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "control-plane",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 0, result.output
    bake_file = tmp_path / "docker-bake.json"
    bake = json.loads(bake_file.read_text(encoding="utf-8"))
    assert set(bake["target"]) == {"control-plane-amd64-jvm"}
    assert (
        f"docker buildx bake --file {bake_file} --print docker-amd64"
        in result.output
    )
    assert "./gradlew :control-plane:bootBuildImage" in result.output


def test_images_plan_honors_flavor_selector(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "v0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "watchdog",
            "--flavor",
            "default",
            "--arch",
            "arm64",
        ],
    )

    assert result.exit_code == 0, result.output
    bake = json.loads((tmp_path / "docker-bake.json").read_text(encoding="utf-8"))
    assert set(bake["target"]) == {"watchdog-arm64-default"}
    assert "docker-arm64" in result.output
    assert "docker-amd64" not in result.output


def test_images_plan_supports_native_only_target_selection(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "control-plane",
            "--flavor",
            "native",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 0, result.output
    bake = json.loads((tmp_path / "docker-bake.json").read_text(encoding="utf-8"))
    assert bake["target"] == {}
    assert "docker buildx bake" not in result.output
    assert "./gradlew :control-plane:bootBuildImage" in result.output


def test_images_plan_rejects_target_flavor_without_cells(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "control-plane",
            "--flavor",
            "default",
        ],
    )

    assert result.exit_code != 0
    assert "selection produced no image cells" in result.output


def test_images_plan_rejects_unknown_targets(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "missing",
        ],
    )

    assert result.exit_code != 0
    assert "unknown image target: missing" in result.output


def test_images_build_rejects_official_ghcr_registry(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--registry",
            "ghcr.io/miciav/nanofaas",
            "--dry-run",
        ],
    )

    assert result.exit_code != 0
    assert "official GHCR registry is reserved for release promotion" in result.output


def test_images_plan_rejects_official_ghcr_registry(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--registry",
            "ghcr.io/miciav/nanofaas",
        ],
    )

    assert result.exit_code != 0
    assert "official GHCR registry is reserved for release promotion" in result.output
    assert not (tmp_path / "docker-bake.json").exists()


def test_build_specs_use_one_bake_group_per_arch_and_verify_all_cells(
    tmp_path: Path,
) -> None:
    plan = build_image_plan(default_tool_paths().workspace_root, "0.18.0")

    specs = images_module._build_specs(
        plan,
        tmp_path / "docker-bake.json",
        builder_role="stack",
        verify_role="stack",
        push=False,
    )

    bake_specs = [spec for spec in specs if spec.task_id.startswith("images.bake.")]
    assert [spec.task_id for spec in bake_specs] == [
        "images.bake.amd64",
        "images.bake.arm64",
    ]
    assert all("--load" in spec.argv for spec in bake_specs)
    verification = next(spec for spec in specs if spec.task_id == "images.verify")
    assert verification.summary == "Verify 52 logical image digests"
    assert verification.argv[:3] == (
        "docker",
        "image",
        "inspect",
    )
    assert len(verification.argv[4:]) == 52


def test_images_build_dry_run_renders_build_without_execution(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "watchdog",
            "--arch",
            "amd64",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0, result.output
    assert "docker buildx bake" in result.output
    assert "--load docker-amd64" in result.output
    assert "--print" not in result.output


def test_images_build_deduplicates_repeated_architecture_selection(
    tmp_path: Path,
) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "control-plane",
            "--arch",
            "amd64",
            "--arch",
            "amd64",
            "--push",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0, result.output
    task_ids = [line.split(maxsplit=1)[0] for line in result.output.splitlines()]
    assert len(task_ids) == len(set(task_ids))
    assert task_ids.count("images.bake.amd64") == 1
    assert task_ids.count("images.gradle.control-plane.amd64") == 1
    assert sorted(task_id for task_id in task_ids if task_id.startswith("images.push.")) == [
        "images.push.control-plane.amd64.jvm",
        "images.push.control-plane.amd64.native",
    ]
    assert "arm64" not in result.output


def test_images_build_loads_environment_and_runs_workflow(
    monkeypatch, tmp_path: Path
) -> None:
    environment_path = tmp_path / "environment.yaml"
    environment_path.write_text("provider: local\n", encoding="utf-8")
    workflow = SimpleNamespace(run=MagicMock())
    build_workflow = MagicMock(return_value=workflow)
    monkeypatch.setattr(images_module, "build_image_workflow", build_workflow)

    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--environment",
            str(environment_path),
            "--run-dir",
            str(tmp_path / "run"),
            "--target",
            "watchdog",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 0, result.output
    assert build_workflow.call_args.kwargs["environment"] == EnvironmentConfig(
        provider="local"
    )
    workflow.run.assert_called_once_with()


def test_images_build_reports_workflow_progress(monkeypatch, tmp_path: Path) -> None:
    task = SimpleNamespace(
        task_id="images.test",
        title="Test image task",
        run=MagicMock(),
    )
    monkeypatch.setattr(
        images_module,
        "build_image_workflow",
        MagicMock(return_value=Workflow(tasks=[task])),
    )

    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "watchdog",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 0, result.output
    assert "[images.test] running" in result.output
    assert "[images.test] passed" in result.output


def test_images_build_propagates_workflow_failure(monkeypatch, tmp_path: Path) -> None:
    workflow = SimpleNamespace(run=MagicMock(side_effect=RuntimeError("bake failed")))
    monkeypatch.setattr(
        images_module,
        "build_image_workflow",
        MagicMock(return_value=workflow),
    )

    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "watchdog",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 1
    assert isinstance(result.exception, RuntimeError)
    assert str(result.exception) == "bake failed"


def test_images_build_push_requires_stack_local_registry(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--registry",
            "registry.example/nanofaas",
            "--push",
            "--dry-run",
        ],
    )

    assert result.exit_code != 0
    assert "--push requires a stack-local registry" in result.output


def test_images_build_allows_explicit_stack_local_push(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "build",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--target",
            "watchdog",
            "--arch",
            "amd64",
            "--push",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0, result.output
    assert (
        "docker push localhost:5000/nanofaas/watchdog:v0.18.0-amd64"
        in result.output
    )


def test_images_plan_rejects_unconfigured_loadgen_builder(tmp_path: Path) -> None:
    result = CliRunner().invoke(
        app,
        [
            "images",
            "plan",
            "0.18.0",
            "--run-dir",
            str(tmp_path),
            "--builder-role",
            "loadgen",
        ],
    )

    assert result.exit_code != 0
    assert "loadgen builder requires a loadgen environment role" in result.output


class _RecordingProvider:
    def __init__(
        self,
        *,
        builder_digests: str = "sha256:a\nsha256:b\n",
        stack_digests: str = "sha256:a\nsha256:b\n",
        fail_load: bool = False,
        fail_transfer_from: bool = False,
        fail_transfer_to: bool = False,
    ) -> None:
        self.builder_digests = builder_digests
        self.stack_digests = stack_digests
        self.fail_load = fail_load
        self.fail_transfer_from = fail_transfer_from
        self.fail_transfer_to = fail_transfer_to
        self.actions: list[tuple[object, ...]] = []

    def exec_argv(self, request, argv, *, env=None, cwd=None, dry_run=False):
        command = tuple(argv)
        self.actions.append(("exec", request.name, command))
        if command[:3] == ("docker", "image", "inspect"):
            stdout = (
                self.builder_digests
                if request.name == "builder"
                else self.stack_digests
            )
            return SimpleNamespace(return_code=0, stdout=stdout, stderr="")
        if command[:2] == ("docker", "load") and self.fail_load:
            return SimpleNamespace(return_code=1, stdout="", stderr="load failed")
        return SimpleNamespace(return_code=0, stdout="", stderr="")

    def transfer_from(self, request, *, source, destination):
        self.actions.append(("transfer_from", request.name, source, destination))
        if self.fail_transfer_from:
            raise RuntimeError("download exploded")
        destination.write_bytes(b"archive")
        return SimpleNamespace(return_code=0, stdout="", stderr="")

    def transfer_to(self, request, *, source, destination):
        self.actions.append(("transfer_to", request.name, source, destination))
        if self.fail_transfer_to:
            raise RuntimeError("upload exploded")
        return SimpleNamespace(return_code=0, stdout="", stderr="")


def _transport_task(tmp_path: Path, provider: _RecordingProvider):
    return images_module.ImageArchiveTransportTask(
        task_id="images.transport",
        title="Transfer images",
        provider=provider,
        builder_request=VmRequest(lifecycle="multipass", name="builder"),
        stack_request=VmRequest(lifecycle="multipass", name="stack"),
        images=("localhost:5000/nanofaas/a:v1", "localhost:5000/nanofaas/b:v1"),
        local_archive=tmp_path / "images.tar",
        builder_archive="/tmp/builder-images.tar",
        stack_archive="/tmp/stack-images.tar",
    )


def test_image_archive_transport_loads_verifies_and_cleans_all_copies(
    tmp_path: Path,
) -> None:
    provider = _RecordingProvider()
    task = _transport_task(tmp_path, provider)

    task.run()

    action_names = [action[0] for action in provider.actions]
    assert action_names.count("transfer_from") == 1
    assert action_names.count("transfer_to") == 1
    stack_mkdir = next(
        index
        for index, action in enumerate(provider.actions)
        if action[:2] == ("exec", "stack") and action[2][:2] == ("mkdir", "-p")
    )
    stack_upload = next(
        index
        for index, action in enumerate(provider.actions)
        if action[0] == "transfer_to"
    )
    assert stack_mkdir < stack_upload
    assert any(
        action[:2] == ("exec", "stack")
        and action[2][:2] == ("docker", "load")
        for action in provider.actions
    )
    cleanup_targets = {
        action[2][-1]
        for action in provider.actions
        if action[0] == "exec" and action[2][:2] == ("rm", "-f")
    }
    assert cleanup_targets == {"/tmp/builder-images.tar", "/tmp/stack-images.tar"}
    assert not (tmp_path / "images.tar").exists()


def test_image_archive_transport_cleans_after_load_failure(tmp_path: Path) -> None:
    provider = _RecordingProvider(fail_load=True)
    task = _transport_task(tmp_path, provider)

    with pytest.raises(RuntimeError, match="load failed"):
        task.run()

    assert sum(
        action[0] == "exec" and action[2][:2] == ("rm", "-f")
        for action in provider.actions
    ) == 2
    assert not (tmp_path / "images.tar").exists()


def test_image_archive_transport_cleans_after_transfer_from_raises(
    tmp_path: Path,
) -> None:
    provider = _RecordingProvider(fail_transfer_from=True)
    task = _transport_task(tmp_path, provider)

    with pytest.raises(RuntimeError, match="download exploded"):
        task.run()

    assert [action[0] for action in provider.actions].count("transfer_from") == 1
    assert [action[0] for action in provider.actions].count("transfer_to") == 0
    cleanup_targets = {
        action[2][-1]
        for action in provider.actions
        if action[0] == "exec" and action[2][:2] == ("rm", "-f")
    }
    assert cleanup_targets == {"/tmp/builder-images.tar", "/tmp/stack-images.tar"}
    assert not (tmp_path / "images.tar").exists()


def test_image_archive_transport_preserves_preexisting_local_archive(
    tmp_path: Path,
) -> None:
    archive = tmp_path / "images.tar"
    archive.write_bytes(b"user-owned archive")
    provider = _RecordingProvider(fail_transfer_from=True)
    task = _transport_task(tmp_path, provider)

    with pytest.raises(FileExistsError, match="refusing to overwrite"):
        task.run()

    assert archive.read_bytes() == b"user-owned archive"
    assert provider.actions == []


def test_image_archive_transport_cleans_after_transfer_to_raises(
    tmp_path: Path,
) -> None:
    provider = _RecordingProvider(fail_transfer_to=True)
    task = _transport_task(tmp_path, provider)

    with pytest.raises(RuntimeError, match="upload exploded"):
        task.run()

    assert [action[0] for action in provider.actions].count("transfer_from") == 1
    assert [action[0] for action in provider.actions].count("transfer_to") == 1
    cleanup_targets = {
        action[2][-1]
        for action in provider.actions
        if action[0] == "exec" and action[2][:2] == ("rm", "-f")
    }
    assert cleanup_targets == {"/tmp/builder-images.tar", "/tmp/stack-images.tar"}
    assert not (tmp_path / "images.tar").exists()


def test_image_archive_transport_rejects_digest_mismatch_and_cleans(
    tmp_path: Path,
) -> None:
    provider = _RecordingProvider(stack_digests="sha256:a\nsha256:changed\n")
    task = _transport_task(tmp_path, provider)

    with pytest.raises(RuntimeError, match="image digest mismatch after transport"):
        task.run()

    assert sum(
        action[0] == "exec" and action[2][:2] == ("rm", "-f")
        for action in provider.actions
    ) == 2
    assert not (tmp_path / "images.tar").exists()


def test_image_archive_transport_rejects_incomplete_digest_inventory(
    tmp_path: Path,
) -> None:
    provider = _RecordingProvider(builder_digests="")
    task = _transport_task(tmp_path, provider)

    with pytest.raises(RuntimeError, match="expected 2 image digests, got 0"):
        task.run()

    assert not (tmp_path / "images.tar").exists()


class _Executor:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail

    def run(self, task, *, dry_run=False):
        return TaskResult(
            task_id=task.task_id,
            status="failed" if self.fail else "passed",
            return_code=1 if self.fail else 0,
            stderr="build failed" if self.fail else "",
        )


def _two_vm_environment() -> EnvironmentConfig:
    return EnvironmentConfig.model_validate(
        {
            "provider": "multipass",
            "roles": {
                "stack": {"name": "stack"},
                "loadgen": {"name": "builder"},
            },
        }
    )


def test_distinct_builder_workflow_stages_bake_transports_and_pushes_on_stack(
    monkeypatch, tmp_path: Path
) -> None:
    plan = build_image_plan(
        default_tool_paths().workspace_root,
        "0.18.0",
        selectors=("watchdog",),
        architectures=("amd64",),
    )
    bake_file = tmp_path / "docker-bake.json"
    bake_file.write_text("{}\n", encoding="utf-8")
    executor = _Executor()
    monkeypatch.setattr(
        images_module,
        "build_role_bindings",
        MagicMock(
            return_value=(
                RoleBindings(host=executor, stack=executor, loadgen=executor),
                None,
            )
        ),
    )
    provider = _RecordingProvider(builder_digests="sha256:a\n", stack_digests="sha256:a\n")

    workflow = images_module.build_image_workflow(
        plan,
        bake_file,
        environment=_two_vm_environment(),
        builder_role="loadgen",
        repo_root=default_tool_paths().workspace_root,
        push=True,
        provider=provider,
    )

    assert workflow.task_ids == [
        "images.bake.stage",
        "images.bake.amd64",
        "images.transport",
        "images.push.watchdog.amd64.default",
        "images.bake.cleanup",
    ]
    transport = workflow.tasks[2]
    assert isinstance(transport, images_module.ImageArchiveTransportTask)
    assert transport.images == (plan.cells[0].image,)
    assert workflow.tasks[1].spec.execution_role == "loadgen"
    assert workflow.tasks[3].spec.execution_role == "stack"


def test_remote_bake_file_is_cleaned_when_build_fails(monkeypatch, tmp_path: Path) -> None:
    plan = build_image_plan(
        default_tool_paths().workspace_root,
        "0.18.0",
        selectors=("watchdog",),
        architectures=("amd64",),
    )
    bake_file = tmp_path / "docker-bake.json"
    bake_file.write_text("{}\n", encoding="utf-8")
    passing = _Executor()
    failing = _Executor(fail=True)
    monkeypatch.setattr(
        images_module,
        "build_role_bindings",
        MagicMock(
            return_value=(
                RoleBindings(host=passing, stack=passing, loadgen=failing),
                None,
            )
        ),
    )
    provider = _RecordingProvider()
    workflow = images_module.build_image_workflow(
        plan,
        bake_file,
        environment=_two_vm_environment(),
        builder_role="loadgen",
        repo_root=default_tool_paths().workspace_root,
        push=False,
        provider=provider,
    )

    with pytest.raises(RuntimeError, match="build failed"):
        workflow.run()

    assert any(action[0] == "transfer_to" for action in provider.actions)
    assert any(
        action[0] == "exec" and action[2][:2] == ("rm", "-f")
        for action in provider.actions
    )
