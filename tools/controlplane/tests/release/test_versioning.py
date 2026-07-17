from __future__ import annotations

import shutil
from pathlib import Path

import pytest

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


@pytest.fixture
def source_tree(tmp_path: Path) -> Path:
    for relative_path in CURATED_FILES:
        destination = tmp_path / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE_REPO / relative_path, destination)
    return tmp_path


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
    chart.write_text(chart.read_text(encoding="utf-8").replace("version: 0.17.0", "version: 0.16.0"), encoding="utf-8")

    with pytest.raises(ValueError, match="Chart.yaml"):
        verify_version_consistency(source_tree)


def test_prepare_version_updates_each_curated_location_without_reformatting(source_tree: Path) -> None:
    before = {
        relative_path: (source_tree / relative_path).read_text(encoding="utf-8")
        for relative_path in CURATED_FILES
    }

    changed = prepare_version(source_tree, "v0.18.0")

    assert changed == tuple(source_tree / relative_path for relative_path in CURATED_FILES)
    for relative_path, original in before.items():
        updated = (source_tree / relative_path).read_text(encoding="utf-8")
        assert updated == original.replace("v0.17.0", "v0.18.0").replace("0.17.0", "0.18.0")
    assert verify_version_consistency(source_tree) == "0.18.0"


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
    before = {relative_path: (source_tree / relative_path).read_bytes() for relative_path in CURATED_FILES}

    with pytest.raises(ValueError, match="replacement count"):
        prepare_version(source_tree, "0.18.0")

    assert {relative_path: (source_tree / relative_path).read_bytes() for relative_path in CURATED_FILES} == before
