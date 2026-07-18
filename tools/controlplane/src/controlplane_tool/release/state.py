"""Append-only, digest-verified local state for image release runs."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any


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
    "publish",
    "attest",
    "finalize",
)
_DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
_ENTRY_NAME = re.compile(r"(?P<sequence>[0-9]{3})-(?P<phase>[a-z0-9-]+)\.json\Z")
_SENSITIVE_KEY = re.compile(r"(?:credential|password|secret|token|authorization|dockerconfig)", re.I)
_KNOWN_FIXTURE_SECRETS = (
    "fixture-secret-must-not-leak",
    "fixture-ghcr-token-must-not-leak",
)


class JournalCorruptionError(ValueError):
    """Raised when an existing release journal cannot be trusted."""


class ResumeValidationError(ValueError):
    """Raised when callers provide invalid release evidence."""


@dataclass(frozen=True, slots=True)
class ReleaseIdentity:
    """Values that must match exactly before a phase may be reused."""

    source_commit: str
    prepared_version: str
    release_config_digest: str
    environment_digest: str

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[0-9a-f]{40}", self.source_commit):
            raise ResumeValidationError("source commit must be a 40-character lowercase SHA")
        if not self.prepared_version:
            raise ResumeValidationError("prepared version must not be empty")
        for field in ("release_config_digest", "environment_digest"):
            value = getattr(self, field)
            if not _DIGEST.fullmatch(value):
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
        if self.location not in {"local", "remote"}:
            raise ResumeValidationError("artifact location must be local or remote")
        if not self.reference:
            raise ResumeValidationError("artifact reference must not be empty")
        if not _DIGEST.fullmatch(self.digest):
            raise ValueError("artifact digest must be a sha256 digest")

    def as_entry(self) -> dict[str, str]:
        return {
            "location": self.location,
            "reference": self.reference,
            "digest": self.digest,
        }


@dataclass(frozen=True, slots=True)
class ResumePlan:
    """Verified completed work and the earliest phase that must run again."""

    reusable_phases: tuple[str, ...]
    restart_phase: str | None
    invalidated_phases: tuple[str, ...]


ArtifactDigest = Callable[[str, str], str | None]


class ReleaseJournal:
    """Persist release boundaries without retaining credentials or mutable success flags."""

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
        """Load and validate every visible journal record in append order."""
        if not self.state_directory.exists():
            return ()
        paths = sorted(self.state_directory.glob("*.json"), key=_entry_sort_key)
        entries: list[dict[str, Any]] = []
        previous_sequence = 0
        for path in paths:
            match = _ENTRY_NAME.fullmatch(path.name)
            if match is None:
                raise JournalCorruptionError(f"invalid journal entry name: {path.name}")
            sequence = int(match["sequence"])
            if sequence != previous_sequence + 1:
                raise JournalCorruptionError("journal entries must have contiguous sequence numbers")
            previous_sequence = sequence
            payload = _read_json(path)
            _validate_entry(payload, self.phases, filename_phase=match["phase"])
            entries.append(payload)
        _validate_history_order(entries, self.phases)
        return tuple(entries)

    def record(
        self,
        phase: str,
        *,
        artifacts: Iterable[ArtifactEvidence] = (),
        outcome: str = "passed",
        metadata: Mapping[str, Any] | None = None,
    ) -> Path:
        """Atomically append the result for the only phase currently eligible to run."""
        if outcome not in {"passed", "failed"}:
            raise ValueError("record outcome must be passed or failed")
        entries = self.entries()
        expected = _next_phase(entries, self.phases)
        if phase != expected:
            raise ValueError(f"phase order violation: expected {expected}, got {phase}")
        payload = _entry_payload(
            self.identity,
            phase,
            outcome,
            tuple(artifacts),
            metadata=metadata,
        )
        _reject_sensitive(payload)
        if outcome == "passed" and not payload["artifacts"]:
            raise ValueError("passed phases require digest-bearing artifact evidence")
        return self._append(payload, len(entries) + 1)

    def resume(self) -> ResumePlan:
        """Return only phases whose identity and every recorded artifact still verify.

        If evidence is missing or changed, append invalidation records for the
        earliest affected phase and all completed downstream phases.  A future
        run can therefore only restart from the first untrusted boundary.
        """
        entries = self.entries()
        latest = _latest_outcomes(entries, self.phases)
        reusable: list[str] = []
        invalid_from: int | None = None

        for index, phase in enumerate(self.phases):
            entry = latest.get(phase)
            if entry is None:
                break
            if entry["outcome"] != "passed":
                break
            if entry["release"] != self.identity.as_entry() or not self._artifacts_verify(entry):
                invalid_from = index
                break
            reusable.append(phase)

        if invalid_from is None:
            restart = self.phases[len(reusable)] if len(reusable) < len(self.phases) else None
            return ResumePlan(tuple(reusable), restart, ())

        invalidated = tuple(
            phase
            for phase in self.phases[invalid_from:]
            if latest.get(phase, {}).get("outcome") == "passed"
        )
        for phase in invalidated:
            payload = _entry_payload(
                self.identity,
                phase,
                "invalidated",
                (),
                metadata={"reason": "resume evidence no longer matches"},
            )
            _reject_sensitive(payload)
            self._append(payload, len(self.entries()) + 1)
        return ResumePlan(tuple(reusable), self.phases[invalid_from], invalidated)

    def _artifacts_verify(self, entry: Mapping[str, Any]) -> bool:
        for artifact in entry["artifacts"]:
            actual = self._current_artifact_digest(artifact["location"], artifact["reference"])
            if actual != artifact["digest"]:
                return False
        return True

    def _current_artifact_digest(self, location: str, reference: str) -> str | None:
        if location == "local":
            path = Path(reference)
            return digest_path(path) if path.is_file() else None
        if self._artifact_digest is None:
            return None
        return self._artifact_digest(location, reference)

    def _append(self, payload: Mapping[str, Any], sequence: int) -> Path:
        self.state_directory.mkdir(parents=True, exist_ok=True)
        filename = f"{sequence:03d}-{payload['phase']}.json"
        destination = self.state_directory / filename
        if destination.exists():
            raise JournalCorruptionError(f"journal entry already exists: {filename}")
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{filename}.", suffix=".tmp", dir=self.state_directory
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
        finally:
            if temporary.exists():
                temporary.unlink()
        return destination


def digest_path(path: Path) -> str:
    """Return the content digest of a regular local evidence file."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return f"sha256:{digest.hexdigest()}"


def _entry_payload(
    identity: ReleaseIdentity,
    phase: str,
    outcome: str,
    artifacts: Sequence[ArtifactEvidence],
    *,
    metadata: Mapping[str, Any] | None,
) -> dict[str, Any]:
    now = _timestamp()
    payload: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "release": identity.as_entry(),
        "phase": phase,
        "artifacts": [artifact.as_entry() for artifact in artifacts],
        "startedAt": now,
        "completedAt": now,
        "outcome": outcome,
    }
    if metadata:
        payload["metadata"] = dict(metadata)
    return payload


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


def _validate_entry(payload: Mapping[str, Any], phases: Sequence[str], *, filename_phase: str) -> None:
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        raise JournalCorruptionError("unsupported schema version")
    required = {
        "schemaVersion",
        "release",
        "phase",
        "artifacts",
        "startedAt",
        "completedAt",
        "outcome",
    }
    if not required.issubset(payload):
        raise JournalCorruptionError("journal entry is missing required fields")
    if payload["phase"] not in phases or payload["phase"] != filename_phase:
        raise JournalCorruptionError("journal entry has an invalid phase")
    if payload["outcome"] not in {"passed", "failed", "invalidated"}:
        raise JournalCorruptionError("journal entry has an invalid outcome")
    try:
        release = payload["release"]
        if not isinstance(release, Mapping):
            raise TypeError
        ReleaseIdentity(
            source_commit=str(release["sourceCommit"]),
            prepared_version=str(release["preparedVersion"]),
            release_config_digest=str(release["releaseConfigDigest"]),
            environment_digest=str(release["environmentDigest"]),
        )
        artifacts = payload["artifacts"]
        if not isinstance(artifacts, list):
            raise TypeError
        for artifact in artifacts:
            if not isinstance(artifact, Mapping):
                raise TypeError
            ArtifactEvidence(
                str(artifact["location"]),
                str(artifact["reference"]),
                str(artifact["digest"]),
            )
    except (KeyError, TypeError, ValueError) as error:
        raise JournalCorruptionError("journal entry has invalid evidence") from error
    _reject_sensitive(payload, corruption=True)


def _latest_outcomes(
    entries: Sequence[Mapping[str, Any]], phases: Sequence[str]
) -> dict[str, Mapping[str, Any]]:
    latest: dict[str, Mapping[str, Any]] = {}
    for entry in entries:
        phase = str(entry["phase"])
        latest[phase] = entry
    return latest


def _next_phase(entries: Sequence[Mapping[str, Any]], phases: Sequence[str]) -> str:
    latest = _latest_outcomes(entries, phases)
    expected = _next_phase_from_latest(latest, phases)
    if expected is None:
        raise ValueError("all release phases are already complete")
    return expected


def _next_phase_from_latest(
    latest: Mapping[str, Mapping[str, Any]], phases: Sequence[str]
) -> str | None:
    for phase in phases:
        if latest.get(phase, {}).get("outcome") != "passed":
            return phase
    return None


def _validate_history_order(
    entries: Sequence[Mapping[str, Any]], phases: Sequence[str]
) -> None:
    latest: dict[str, Mapping[str, Any]] = {}
    invalidation_position: int | None = None
    phase_positions = {phase: index for index, phase in enumerate(phases)}
    for entry in entries:
        phase = str(entry["phase"])
        if entry["outcome"] == "invalidated":
            if latest.get(phase, {}).get("outcome") != "passed":
                raise JournalCorruptionError("journal invalidation has no completed phase")
            position = phase_positions[phase]
            if invalidation_position is not None and position <= invalidation_position:
                raise JournalCorruptionError("journal invalidations are not in phase order")
            invalidation_position = position
        else:
            expected = _next_phase_from_latest(latest, phases)
            if phase != expected:
                raise JournalCorruptionError("journal phase order is invalid")
            invalidation_position = None
        latest[phase] = entry


def _reject_sensitive(value: Any, *, corruption: bool = False) -> None:
    try:
        _find_sensitive(value)
    except ValueError as error:
        if corruption:
            raise JournalCorruptionError("journal entry contains sensitive data") from error
        raise


def _find_sensitive(value: Any, *, key: str | None = None) -> None:
    if key is not None and _SENSITIVE_KEY.search(key):
        raise ValueError("sensitive data is forbidden in the release journal")
    if isinstance(value, Mapping):
        for item_key, item_value in value.items():
            _find_sensitive(item_value, key=str(item_key))
    elif isinstance(value, (list, tuple)):
        for item in value:
            _find_sensitive(item)
    elif isinstance(value, str) and any(secret in value for secret in _KNOWN_FIXTURE_SECRETS):
        raise ValueError("sensitive data is forbidden in the release journal")
