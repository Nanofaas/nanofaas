from __future__ import annotations

import inspect
import shutil
import subprocess
from pathlib import Path
from typing import Callable

import pytest

from controlplane_tool.release import versioning
from controlplane_tool.release.versioning import (
    normalize_version,
    prepare_version,
    read_project_version,
    verify_version_consistency,
)


SOURCE_REPO = Path(__file__).resolve().parents[4]
CURATED_FILES = (
    Path("build.gradle"),
    Path("deploy/helm/nanofaas/Chart.yaml"),
    Path("deploy/helm/nanofaas/values.yaml"),
    Path("deploy/k8s/control-plane-deployment.yaml"),
    Path("runtimes/watchdog/Cargo.toml"),
    Path("runtimes/watchdog/Cargo.lock"),
    Path("sdks/python/pyproject.toml"),
    Path("sdks/python/uv.lock"),
    Path("functions/python/roman-numeral/uv.lock"),
    Path("tools/fn-init/src/fn_init/main.py"),
    Path("clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java"),
)
LOCKFILE_COMMANDS = (
    (("cargo", "check"), Path("runtimes/watchdog"), Path("runtimes/watchdog/Cargo.lock")),
    (("uv", "lock"), Path("sdks/python"), Path("sdks/python/uv.lock")),
    (
        ("uv", "lock"),
        Path("functions/python/roman-numeral"),
        Path("functions/python/roman-numeral/uv.lock"),
    ),
)


@pytest.fixture
def source_tree(tmp_path: Path) -> Path:
    for relative_path in CURATED_FILES:
        destination = tmp_path / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE_REPO / relative_path, destination)
    sentinel = tmp_path / "docs" / "release-sentinel.txt"
    sentinel.parent.mkdir(parents=True, exist_ok=True)
    sentinel.write_text("must remain unchanged\n", encoding="utf-8")
    return tmp_path


def lockfile_runner(
    repo_root: Path, calls: list[tuple[tuple[str, ...], Path]]
) -> Callable[[tuple[str, ...], Path], None]:
    lockfiles = {cwd: lockfile for _, cwd, lockfile in LOCKFILE_COMMANDS}

    def run(command: tuple[str, ...], cwd: Path) -> None:
        calls.append((command, cwd.relative_to(repo_root)))
        lockfile = repo_root / lockfiles[cwd.relative_to(repo_root)]
        assert "0.17.0" in lockfile.read_text(encoding="utf-8")
        lockfile.write_text(
            lockfile.read_text(encoding="utf-8").replace("0.17.0", "0.18.0"),
            encoding="utf-8",
        )

    return run


def install_lockfile_runner(
    monkeypatch: pytest.MonkeyPatch,
    repo_root: Path,
    calls: list[tuple[tuple[str, ...], Path]],
) -> None:
    monkeypatch.setattr(versioning, "_run_command", lockfile_runner(repo_root, calls))


def test_normalize_version_returns_plain_and_image_tag() -> None:
    assert normalize_version("v0.18.0") == ("0.18.0", "v0.18.0")
    assert normalize_version("0.18.0") == ("0.18.0", "v0.18.0")


@pytest.mark.parametrize("value", ("", "v0.18", "0.18.0-rc1", "v0.18.0.1", "v01.18.0"))
def test_normalize_version_rejects_invalid_versions(value: str) -> None:
    with pytest.raises(ValueError, match="version"):
        normalize_version(value)


def test_read_project_version_reads_root_gradle_version(source_tree: Path) -> None:
    assert read_project_version(source_tree) == "0.17.0"


def test_verify_version_consistency_returns_current_version(source_tree: Path) -> None:
    assert verify_version_consistency(source_tree) == "0.17.0"


def test_verify_version_consistency_rejects_mismatched_source_tree(source_tree: Path) -> None:
    chart = source_tree / "deploy/helm/nanofaas/Chart.yaml"
    chart.write_text(
        chart.read_text(encoding="utf-8").replace("version: 0.17.0", "version: 0.16.0"),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="Chart.yaml"):
        verify_version_consistency(source_tree)


def test_prepare_version_updates_each_curated_location_without_reformatting(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    before = {
        relative_path: (source_tree / relative_path).read_text(encoding="utf-8")
        for relative_path in CURATED_FILES
    }

    install_lockfile_runner(monkeypatch, source_tree, [])
    changed = prepare_version(source_tree, "v0.18.0")

    assert changed == tuple(source_tree / relative_path for relative_path in CURATED_FILES)
    for relative_path, original in before.items():
        updated = (source_tree / relative_path).read_text(encoding="utf-8")
        assert updated == original.replace("v0.17.0", "v0.18.0").replace("0.17.0", "0.18.0")
    assert verify_version_consistency(source_tree) == "0.18.0"


def test_prepare_version_regenerates_lockfiles_after_primary_edits(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[tuple[str, ...], Path]] = []
    install_lockfile_runner(monkeypatch, source_tree, calls)

    prepare_version(source_tree, "0.18.0")

    assert calls == [(command, cwd) for command, cwd, _ in LOCKFILE_COMMANDS]


def test_prepare_version_leaves_non_curated_sentinel_unchanged(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sentinel = source_tree / "docs" / "release-sentinel.txt"
    before = sentinel.read_bytes()

    install_lockfile_runner(monkeypatch, source_tree, [])
    prepare_version(source_tree, "0.18.0")

    assert sentinel.read_bytes() == before


def test_prepare_version_rejects_runner_changes_outside_curated_files(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[tuple[str, ...], Path]] = []
    runner = lockfile_runner(source_tree, calls)
    sentinel = source_tree / "docs" / "release-sentinel.txt"

    def run(command: tuple[str, ...], cwd: Path) -> None:
        runner(command, cwd)
        sentinel.write_text("unexpected runner output\n", encoding="utf-8")

    monkeypatch.setattr(versioning, "_run_command", run)

    with pytest.raises(ValueError, match="outside curated") as error:
        prepare_version(source_tree, "0.18.0")

    assert error.value.__cause__ is None


def test_prepare_version_rejects_runner_scope_changes_when_runner_fails(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sentinel = source_tree / "docs" / "release-sentinel.txt"

    def run(command: tuple[str, ...], cwd: Path) -> None:
        sentinel.write_text("unexpected runner output\n", encoding="utf-8")
        raise subprocess.CalledProcessError(1, command)

    monkeypatch.setattr(versioning, "_run_command", run)

    with pytest.raises(ValueError, match="outside curated") as error:
        prepare_version(source_tree, "0.18.0")

    assert isinstance(error.value.__cause__, subprocess.CalledProcessError)


def test_prepare_version_preserves_runner_error_without_scope_changes(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def run(command: tuple[str, ...], cwd: Path) -> None:
        raise subprocess.CalledProcessError(1, command)

    monkeypatch.setattr(versioning, "_run_command", run)

    with pytest.raises(subprocess.CalledProcessError):
        prepare_version(source_tree, "0.18.0")


def test_prepare_version_rejects_scope_changes_when_final_consistency_check_fails(
    source_tree: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sentinel = source_tree / "docs" / "release-sentinel.txt"
    checks = 0
    original_verify = versioning.verify_version_consistency
    install_lockfile_runner(monkeypatch, source_tree, [])

    def verify(repo_root: Path) -> str:
        nonlocal checks
        checks += 1
        if checks == 1:
            return original_verify(repo_root)
        sentinel.write_text("unexpected verification output\n", encoding="utf-8")
        raise RuntimeError("simulated final consistency failure")

    monkeypatch.setattr(versioning, "verify_version_consistency", verify)

    with pytest.raises(ValueError, match="outside curated") as error:
        prepare_version(source_tree, "0.18.0")

    assert isinstance(error.value.__cause__, RuntimeError)


def test_prepare_version_exposes_only_the_required_public_parameters() -> None:
    assert tuple(inspect.signature(prepare_version).parameters) == ("repo_root", "requested")


@pytest.mark.parametrize("requested", ("0.17.0", "v0.17.0", "0.16.9", "not-a-version"))
def test_prepare_version_rejects_invalid_or_nonincrementing_versions(
    source_tree: Path,
    requested: str,
) -> None:
    with pytest.raises(ValueError):
        prepare_version(source_tree, requested)


def test_prepare_version_rejects_unexpected_replacement_count_without_writing(
    source_tree: Path,
) -> None:
    values = source_tree / "deploy/helm/nanofaas/values.yaml"
    original = values.read_text(encoding="utf-8")
    values.write_text(original + "\n# stale image: v0.17.0\n", encoding="utf-8")
    before = {
        relative_path: (source_tree / relative_path).read_bytes() for relative_path in CURATED_FILES
    }

    with pytest.raises(ValueError, match="replacement count"):
        prepare_version(source_tree, "0.18.0")

    assert {
        relative_path: (source_tree / relative_path).read_bytes() for relative_path in CURATED_FILES
    } == before
