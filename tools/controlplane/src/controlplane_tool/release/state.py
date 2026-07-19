"""Append-only, digest-verified local state for image release runs."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator, Mapping, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any

from controlplane_tool.release.versioning import normalize_version


SCHEMA_VERSION = 1
DEFAULT_RELEASE_PHASES = (
    "source-tests",
    "amd64-build",
    "local-registry-push",
    "benchmark-1",
    "benchmark-2",
    "benchmark-3",
    "aggregate",
    "regression-gate",
    "arm64-build",
    "arm64-smoke",
    "publish-architectures",
    "publish-manifests",
    "publish-aliases",
    "attest",
    "finalize",
)
_DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
_ENTRY_NAME = re.compile(r"(?P<sequence>[0-9]{3})-(?P<label>[a-z0-9-]+)\.json\Z")
_INVALIDATION_REASON = "resume-evidence-mismatch"
_KNOWN_FIXTURE_SECRETS = (
    "fixture-secret-must-not-leak",
    "fixture-ghcr-token-must-not-leak",
    "fixture-cosign-key-must-not-leak",
    "fixture-cosign-password-must-not-leak",
)


class JournalCorruptionError(ValueError):
    """Raised when existing release state cannot be trusted."""


class ResumeValidationError(ValueError):
    """Raised when a caller supplies invalid release evidence."""


@dataclass(frozen=True, slots=True)
class ReleaseIdentity:
    """Values that must match exactly before a phase may be reused."""

    source_commit: str
    prepared_version: str
    release_config_digest: str
    environment_digest: str

    def __post_init__(self) -> None:
        _reject_fixture_secret(self.source_commit)
        _reject_fixture_secret(self.prepared_version)
        _reject_fixture_secret(self.release_config_digest)
        _reject_fixture_secret(self.environment_digest)
        if not re.fullmatch(r"[0-9a-f]{40}", self.source_commit):
            raise ResumeValidationError("source commit must be a 40-character lowercase SHA")
        try:
            normalized, _ = normalize_version(self.prepared_version)
        except ValueError as error:
            raise ResumeValidationError("prepared version must be a semantic version") from error
        if normalized != self.prepared_version:
            raise ResumeValidationError("prepared version must not use a container-tag prefix")
        for field in ("release_config_digest", "environment_digest"):
            if not _DIGEST.fullmatch(getattr(self, field)):
                raise ResumeValidationError(f"{field} must be a sha256 digest")

    def as_entry(self) -> dict[str, str]:
        return {
            "sourceCommit": self.source_commit,
            "preparedVersion": self.prepared_version,
            "releaseConfigDigest": self.release_config_digest,
            "environmentDigest": self.environment_digest,
        }


@dataclass(frozen=True, slots=True)
class ArtifactEvidence:
    """A local file or remote object whose digest makes a phase reusable."""

    location: str
    reference: str
    digest: str

    def __post_init__(self) -> None:
        _reject_fixture_secret(self.location)
        _reject_fixture_secret(self.reference)
        _reject_fixture_secret(self.digest)
        if self.location not in {"local", "remote"}:
            raise ResumeValidationError("artifact location must be local or remote")
        if not self.reference:
            raise ResumeValidationError("artifact reference must not be empty")
        if not _DIGEST.fullmatch(self.digest):
            raise ValueError("artifact digest must be a sha256 digest")

    def as_entry(self) -> dict[str, str]:
        return {"location": self.location, "reference": self.reference, "digest": self.digest}


@dataclass(frozen=True, slots=True)
class ResumePlan:
    reusable_phases: tuple[str, ...]
    restart_phase: str | None
    invalidated_phases: tuple[str, ...]


ArtifactDigest = Callable[[str, str], str | None]


class ReleaseJournal:
    """Single-writer release journal that fails closed on missing evidence."""

    def __init__(
        self,
        runs_directory: Path,
        identity: ReleaseIdentity,
        *,
        phases: Sequence[str] = DEFAULT_RELEASE_PHASES,
        artifact_digest: ArtifactDigest | None = None,
    ) -> None:
        if not phases or len(set(phases)) != len(phases):
            raise ValueError("release phases must be non-empty and unique")
        if any(not re.fullmatch(r"[a-z0-9-]+", phase) for phase in phases):
            raise ValueError("release phases may contain lowercase letters, digits, and hyphens")
        self._runs_directory = Path(runs_directory)
        self.identity = identity
        self.phases = tuple(phases)
        self._artifact_digest = artifact_digest

    @property
    def state_directory(self) -> Path:
        return self._runs_directory / "releases" / self.identity.prepared_version / "state"

    def entries(self) -> tuple[dict[str, Any], ...]:
        """Read visible state; atomic writes make concurrent reads safe."""
        return self._entries()

    def record(
        self,
        phase: str,
        *,
        artifacts: Iterable[ArtifactEvidence] = (),
        outcome: str = "passed",
    ) -> Path:
        """Append one phase result while holding the local single-writer lock."""
        if outcome not in {"passed", "failed"}:
            raise ValueError("record outcome must be passed or failed")
        artifact_list = tuple(artifacts)
        if outcome == "passed" and not artifact_list:
            raise ValueError("passed phases require digest-bearing artifact evidence")
        with self._exclusive_lock():
            entries = self._entries()
            expected = _next_phase(entries, self.phases)
            if phase != expected:
                raise ValueError(f"phase order violation: expected {expected}, got {phase}")
            return self._append(
                _phase_entry(self.identity, phase, outcome, artifact_list), len(entries) + 1
            )

    def resume(self) -> ResumePlan:
        """Verify completed phases, atomically invalidating an untrusted suffix."""
        with self._exclusive_lock():
            entries = self._entries()
            latest = _latest_outcomes(entries, self.phases)
            reusable: list[str] = []
            for phase in self.phases:
                entry = latest.get(phase)
                if entry is None or entry["outcome"] != "passed":
                    break
                if entry["release"] != self.identity.as_entry() or not self._artifacts_verify(entry):
                    break
                reusable.append(phase)

            restart = self.phases[len(reusable)] if len(reusable) < len(self.phases) else None
            if restart is None or latest.get(restart, {}).get("outcome") != "passed":
                return ResumePlan(tuple(reusable), restart, ())

            affected = tuple(
                phase
                for phase in self.phases[len(reusable) :]
                if latest.get(phase, {}).get("outcome") == "passed"
            )
            self._append(
                _invalidation_entry(self.identity, restart, affected), len(entries) + 1
            )
            return ResumePlan(tuple(reusable), restart, affected)

    def _entries(self) -> tuple[dict[str, Any], ...]:
        if not self.state_directory.exists():
            return ()
        entries: list[dict[str, Any]] = []
        previous_sequence = 0
        for path in sorted(self.state_directory.glob("*.json"), key=_entry_sort_key):
            match = _ENTRY_NAME.fullmatch(path.name)
            if match is None:
                raise JournalCorruptionError(f"invalid journal entry name: {path.name}")
            sequence = int(match["sequence"])
            if sequence != previous_sequence + 1:
                raise JournalCorruptionError("journal entries must have contiguous sequence numbers")
            previous_sequence = sequence
            payload = _read_json(path)
            _validate_entry(payload, self.phases, filename_label=match["label"])
            entries.append(payload)
        _validate_history(entries, self.phases)
        return tuple(entries)

    def _artifacts_verify(self, entry: Mapping[str, Any]) -> bool:
        return all(
            self._current_artifact_digest(artifact["location"], artifact["reference"])
            == artifact["digest"]
            for artifact in entry["artifacts"]
        )

    def _current_artifact_digest(self, location: str, reference: str) -> str | None:
        if location == "local":
            path = Path(reference)
            return digest_path(path) if path.is_file() else None
        return self._artifact_digest(location, reference) if self._artifact_digest else None

    @contextmanager
    def _exclusive_lock(self) -> Iterator[None]:
        self.state_directory.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(self.state_directory / ".lock", os.O_RDWR | os.O_CREAT, 0o600)
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeError("release journal is already in use") from error
            yield
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
            os.close(descriptor)

    def _append(self, payload: Mapping[str, Any], sequence: int) -> Path:
        label = (
            str(payload["phase"])
            if "phase" in payload
            else f"invalidation-{payload['invalidateFrom']}"
        )
        destination = self.state_directory / f"{sequence:03d}-{label}.json"
        if destination.exists():
            raise JournalCorruptionError(f"journal entry already exists: {destination.name}")
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{destination.name}.", suffix=".tmp", dir=self.state_directory
        )
        temporary = Path(temporary_name)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, sort_keys=True, separators=(",", ":"))
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, destination)
            directory_fd = os.open(self.state_directory, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if temporary.exists():
                temporary.unlink()
        return destination


def digest_path(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return f"sha256:{digest.hexdigest()}"


def _phase_entry(
    identity: ReleaseIdentity,
    phase: str,
    outcome: str,
    artifacts: Sequence[ArtifactEvidence],
) -> dict[str, Any]:
    now = _timestamp()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "phase",
        "release": identity.as_entry(),
        "phase": phase,
        "artifacts": [artifact.as_entry() for artifact in artifacts],
        "startedAt": now,
        "completedAt": now,
        "outcome": outcome,
    }


def _invalidation_entry(
    identity: ReleaseIdentity, invalidate_from: str, affected: Sequence[str]
) -> dict[str, Any]:
    now = _timestamp()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "invalidation",
        "release": identity.as_entry(),
        "invalidateFrom": invalidate_from,
        "affectedPhases": list(affected),
        "reason": _INVALIDATION_REASON,
        "startedAt": now,
        "completedAt": now,
        "outcome": "invalidated",
    }


def _timestamp() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _entry_sort_key(path: Path) -> int:
    match = _ENTRY_NAME.fullmatch(path.name)
    return int(match["sequence"]) if match else -1


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise JournalCorruptionError(f"invalid JSON in journal entry {path.name}") from error
    if not isinstance(value, dict):
        raise JournalCorruptionError(f"journal entry {path.name} must be an object")
    return value


def _validate_entry(payload: Mapping[str, Any], phases: Sequence[str], *, filename_label: str) -> None:
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        raise JournalCorruptionError("unsupported schema version")
    kind = payload.get("kind")
    if kind == "phase":
        required = {"release", "phase", "artifacts", "startedAt", "completedAt", "outcome"}
        if set(payload) != required | {"schemaVersion", "kind"} or payload["phase"] not in phases:
            raise JournalCorruptionError("journal phase entry has invalid fields")
        if filename_label != payload["phase"] or payload["outcome"] not in {"passed", "failed"}:
            raise JournalCorruptionError("journal phase entry has invalid outcome")
        _validate_release_and_artifacts(payload, require_artifacts=payload["outcome"] == "passed")
        return
    if kind == "invalidation":
        required = {"release", "invalidateFrom", "affectedPhases", "reason", "startedAt", "completedAt", "outcome"}
        if set(payload) != required | {"schemaVersion", "kind"} or payload["outcome"] != "invalidated":
            raise JournalCorruptionError("journal invalidation entry has invalid fields")
        if payload["invalidateFrom"] not in phases or payload["reason"] != _INVALIDATION_REASON:
            raise JournalCorruptionError("journal invalidation entry has invalid boundary")
        if filename_label != f"invalidation-{payload['invalidateFrom']}":
            raise JournalCorruptionError("journal invalidation entry has invalid name")
        affected = payload["affectedPhases"]
        if not isinstance(affected, list) or not all(item in phases for item in affected):
            raise JournalCorruptionError("journal invalidation entry has invalid phases")
        _validate_release_and_artifacts(payload, require_artifacts=False, artifacts_optional=True)
        return
    raise JournalCorruptionError("journal entry has unknown kind")


def _validate_release_and_artifacts(
    payload: Mapping[str, Any], *, require_artifacts: bool, artifacts_optional: bool = False
) -> None:
    try:
        release = payload["release"]
        if not isinstance(release, Mapping) or set(release) != {
            "sourceCommit",
            "preparedVersion",
            "releaseConfigDigest",
            "environmentDigest",
        }:
            raise TypeError
        ReleaseIdentity(
            str(release["sourceCommit"]),
            str(release["preparedVersion"]),
            str(release["releaseConfigDigest"]),
            str(release["environmentDigest"]),
        )
        artifacts = payload.get("artifacts", []) if artifacts_optional else payload["artifacts"]
        if not isinstance(artifacts, list) or (require_artifacts and not artifacts):
            raise TypeError
        for artifact in artifacts:
            if not isinstance(artifact, Mapping) or set(artifact) != {
                "location",
                "reference",
                "digest",
            }:
                raise TypeError
            ArtifactEvidence(str(artifact["location"]), str(artifact["reference"]), str(artifact["digest"]))
    except (KeyError, TypeError, ValueError) as error:
        raise JournalCorruptionError("journal entry has invalid evidence") from error


def _validate_history(entries: Sequence[Mapping[str, Any]], phases: Sequence[str]) -> None:
    latest: dict[str, Mapping[str, Any]] = {}
    for entry in entries:
        if entry["kind"] == "phase":
            expected = _next_phase_from_latest(latest, phases)
            if entry["phase"] != expected:
                raise JournalCorruptionError("journal phase order is invalid")
            latest[entry["phase"]] = entry
            continue
        affected = tuple(entry["affectedPhases"])
        start = phases.index(entry["invalidateFrom"])
        expected_affected = tuple(
            phase for phase in phases[start:] if latest.get(phase, {}).get("outcome") == "passed"
        )
        if affected != expected_affected or not affected:
            raise JournalCorruptionError("journal invalidation must cover the completed suffix")
        for phase in affected:
            latest[phase] = {"outcome": "invalidated"}


def _latest_outcomes(
    entries: Sequence[Mapping[str, Any]], phases: Sequence[str]
) -> dict[str, Mapping[str, Any]]:
    latest: dict[str, Mapping[str, Any]] = {}
    for entry in entries:
        if entry["kind"] == "phase":
            latest[entry["phase"]] = entry
        else:
            latest.update({phase: {"outcome": "invalidated"} for phase in entry["affectedPhases"]})
    return latest


def _next_phase(entries: Sequence[Mapping[str, Any]], phases: Sequence[str]) -> str:
    expected = _next_phase_from_latest(_latest_outcomes(entries, phases), phases)
    if expected is None:
        raise ValueError("all release phases are already complete")
    return expected


def _next_phase_from_latest(
    latest: Mapping[str, Mapping[str, Any]], phases: Sequence[str]
) -> str | None:
    return next((phase for phase in phases if latest.get(phase, {}).get("outcome") != "passed"), None)


def _reject_fixture_secret(value: str) -> None:
    if any(secret in value for secret in _KNOWN_FIXTURE_SECRETS):
        raise ValueError("release journal values must not contain fixture secrets")
