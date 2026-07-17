from __future__ import annotations

import re
from pathlib import Path


_VERSION_PATTERN = re.compile(r"v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
_GRADLE_VERSION_PATTERN = re.compile(r"(?m)^\s*version\s*=\s*'([^']+)'\s*$")
_CURATED_COUNTS = {
    Path("build.gradle"): 1,
    Path("deploy/helm/nanofaas/Chart.yaml"): 2,
    Path("deploy/helm/nanofaas/values.yaml"): 11,
    Path("deploy/k8s/control-plane-deployment.yaml"): 1,
    Path("runtimes/watchdog/Cargo.toml"): 1,
    Path("runtimes/watchdog/Cargo.lock"): 1,
    Path("sdks/python/pyproject.toml"): 1,
    Path("sdks/python/uv.lock"): 1,
    Path("functions/python/roman-numeral/uv.lock"): 1,
    Path("tools/fn-init/src/fn_init/main.py"): 1,
    Path("clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java"): 1,
}


def normalize_version(value: str) -> tuple[str, str]:
    """Return a plain semantic version and its container image tag."""
    match = _VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ValueError(f"invalid version: {value!r}")
    plain = ".".join(match.groups())
    return plain, f"v{plain}"


def read_project_version(repo_root: Path) -> str:
    """Read the root Gradle project version without rewriting its source."""
    build_gradle = repo_root / "build.gradle"
    matches = _GRADLE_VERSION_PATTERN.findall(build_gradle.read_text(encoding="utf-8"))
    if len(matches) != 1:
        raise ValueError(f"expected exactly one project version in {build_gradle}")
    return normalize_version(matches[0])[0]


def verify_version_consistency(repo_root: Path) -> str:
    """Confirm every curated release location contains the root version exactly as expected."""
    current = read_project_version(repo_root)
    _validate_replacement_counts(repo_root, current)
    return current


def prepare_version(repo_root: Path, requested: str) -> tuple[Path, ...]:
    """Prepare all curated release files for a strictly newer semantic version."""
    current = verify_version_consistency(repo_root)
    requested_plain, _ = normalize_version(requested)
    if _version_key(requested_plain) <= _version_key(current):
        raise ValueError(f"requested version {requested_plain} must be newer than {current}")

    updates = _prepared_updates(repo_root, current, requested_plain)
    for path, content in updates:
        path.write_text(content, encoding="utf-8")
    return tuple(path for path, _ in updates)


def _validate_replacement_counts(repo_root: Path, version: str) -> None:
    for relative_path, expected_count in _CURATED_COUNTS.items():
        path = repo_root / relative_path
        actual_count = path.read_text(encoding="utf-8").count(version)
        if actual_count != expected_count:
            raise ValueError(
                f"replacement count in {relative_path} is {actual_count}, expected {expected_count}"
            )


def _prepared_updates(repo_root: Path, current: str, requested: str) -> tuple[tuple[Path, str], ...]:
    updates: list[tuple[Path, str]] = []
    for relative_path, expected_count in _CURATED_COUNTS.items():
        path = repo_root / relative_path
        source = path.read_text(encoding="utf-8")
        if source.count(current) != expected_count:
            raise ValueError(
                f"replacement count in {relative_path} is {source.count(current)}, expected {expected_count}"
            )
        updates.append((path, source.replace(current, requested)))
    return tuple(updates)


def _version_key(version: str) -> tuple[int, int, int]:
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)
