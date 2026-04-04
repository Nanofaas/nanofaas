from __future__ import annotations

from pathlib import Path

from controlplane_tool.runtime_primitives import (
    CommandRunner,
    ContainerRuntimeOps,
    KubectlOps,
    read_json_field,
    write_json_file,
)
from controlplane_tool.shell_backend import RecordingShell


def test_runtime_primitives_wrap_process_execution_without_shell_scripts() -> None:
    runner = CommandRunner(shell=RecordingShell(), repo_root=Path("/repo"))
    result = runner.run(["echo", "hi"], dry_run=True)
    assert result.command == ["echo", "hi"]


def test_command_runner_records_commands() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    runner.run(["echo", "a"], dry_run=True)
    runner.run(["echo", "b"], dry_run=True)
    assert shell.commands == [["echo", "a"], ["echo", "b"]]


def test_container_runtime_ops_build_command_uses_provided_tag() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="docker")
    ops.build(tag="my-image:test", context=Path("/repo/my-app"), dry_run=True)
    assert any("my-image:test" in " ".join(cmd) for cmd in shell.commands)


def test_container_runtime_ops_remove_command_includes_name() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="docker")
    ops.remove("my-container", dry_run=True)
    assert any("my-container" in " ".join(cmd) for cmd in shell.commands)


def test_kubectl_ops_apply_includes_manifest_path() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = KubectlOps(runner=runner)
    ops.apply(Path("/tmp/manifest.yaml"), dry_run=True)
    assert any("/tmp/manifest.yaml" in " ".join(cmd) for cmd in shell.commands)


def test_kubectl_ops_respects_kubeconfig() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = KubectlOps(runner=runner, kubeconfig="/home/user/.kube/config")
    ops.apply(Path("/tmp/manifest.yaml"), dry_run=True)
    rendered = [" ".join(cmd) for cmd in shell.commands]
    assert any("/home/user/.kube/config" in r for r in rendered)


def test_read_json_field_extracts_nested_value(tmp_path: Path) -> None:
    f = tmp_path / "data.json"
    f.write_text('{"a": {"b": "hello"}}', encoding="utf-8")
    assert read_json_field(f, "a.b") == "hello"


def test_write_json_file_roundtrips(tmp_path: Path) -> None:
    f = tmp_path / "out.json"
    write_json_file(f, {"name": "test", "value": 42})
    assert read_json_field(f, "name") == "test"
    assert read_json_field(f, "value") == 42
