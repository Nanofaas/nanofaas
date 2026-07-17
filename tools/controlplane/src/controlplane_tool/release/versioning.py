from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Callable


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
_LOCKFILE_COMMANDS = (
    (("cargo", "check"), Path("runtimes/watchdog")),
    (("uv", "lock"), Path("sdks/python")),
    (("uv", "lock"), Path("functions/python/roman-numeral")),
)
_LOCKFILES = frozenset(
    {
        Path("runtimes/watchdog/Cargo.lock"),
        Path("sdks/python/uv.lock"),
        Path("functions/python/roman-numeral/uv.lock"),
    }
)
_PRIMARY_COUNTS = {
    relative_path: count
    for relative_path, count in _CURATED_COUNTS.items()
    if relative_path not in _LOCKFILES
}
Runner = Callable[[tuple[str, ...], Path], None]


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


def prepare_version(
    repo_root: Path,
    requested: str,
    *,
    runner: Runner | None = None,
) -> tuple[Path, ...]:
    """Prepare all curated release files for a strictly newer semantic version."""
    current = verify_version_consistency(repo_root)
    requested_plain, _ = normalize_version(requested)
    if _version_key(requested_plain) <= _version_key(current):
        raise ValueError(f"requested version {requested_plain} must be newer than {current}")

    updates = _prepared_updates(repo_root, current, requested_plain, _PRIMARY_COUNTS)
    for path, content in updates:
        path.write_text(content, encoding="utf-8")
    run = runner or _run_command
    for command, relative_cwd in _LOCKFILE_COMMANDS:
        run(command, repo_root / relative_cwd)
    verify_version_consistency(repo_root)
    return tuple(repo_root / relative_path for relative_path in _CURATED_COUNTS)


def _validate_replacement_counts(repo_root: Path, version: str) -> None:
    for relative_path, expected_count in _CURATED_COUNTS.items():
        path = repo_root / relative_path
        actual_count = path.read_text(encoding="utf-8").count(version)
        if actual_count != expected_count:
            raise ValueError(
                f"replacement count in {relative_path} is {actual_count}, expected {expected_count}"
            )


def _prepared_updates(
    repo_root: Path,
    current: str,
    requested: str,
    counts: dict[Path, int],
) -> tuple[tuple[Path, str], ...]:
    updates: list[tuple[Path, str]] = []
    for relative_path, expected_count in counts.items():
        path = repo_root / relative_path
        source = path.read_text(encoding="utf-8")
        if source.count(current) != expected_count:
            raise ValueError(
                f"replacement count in {relative_path} is {source.count(current)}, expected {expected_count}"
            )
        updates.append((path, source.replace(current, requested)))
    return tuple(updates)


def _run_command(command: tuple[str, ...], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def _version_key(version: str) -> tuple[int, int, int]:
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)
