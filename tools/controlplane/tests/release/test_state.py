from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

from controlplane_tool.release.state import (
    ArtifactDigest,
    ArtifactEvidence,
    JournalCorruptionError,
    ReleaseIdentity,
    ReleaseJournal,
    ResumePlan,
    ResumeValidationError,
    digest_path,
)


PHASES = ("source-tests", "amd64-build", "benchmark")


def _identity() -> ReleaseIdentity:
    return ReleaseIdentity(
        source_commit="a" * 40,
        prepared_version="0.18.0",
        release_config_digest="sha256:" + "b" * 64,
        environment_digest="sha256:" + "c" * 64,
    )


def _journal(
    tmp_path: Path,
    *,
    identity: ReleaseIdentity | None = None,
    artifact_digest: ArtifactDigest | None = None,
) -> ReleaseJournal:
    return ReleaseJournal(
        tmp_path / "runs", identity or _identity(), phases=PHASES, artifact_digest=artifact_digest
    )


def _artifact(path: Path) -> ArtifactEvidence:
    return ArtifactEvidence("local", str(path), digest_path(path))


def _marker(tmp_path: Path, name: str) -> ArtifactEvidence:
    path = tmp_path / name
    path.write_text(name, encoding="utf-8")
    return _artifact(path)


def _payload(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def test_entry_is_atomic_append_only_and_contains_release_evidence(tmp_path: Path) -> None:
    artifact = _marker(tmp_path, "source")
    journal = _journal(tmp_path)

    entry = journal.record("source-tests", artifacts=(artifact,))

    assert entry.name == "001-source-tests.json"
    assert journal.state_directory == tmp_path / "runs/releases/0.18.0/state"
    payload = _payload(entry)
    assert payload["kind"] == "phase"
    assert payload["outcome"] == "passed"
    assert payload["artifacts"] == [artifact.as_entry()]
    assert payload["release"] == _identity().as_entry()
    assert not tuple(journal.state_directory.glob("*.tmp"))


def test_interrupted_atomic_write_never_creates_a_visible_entry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    journal = _journal(tmp_path)
    module = __import__("controlplane_tool.release.state", fromlist=["os"])
    monkeypatch.setattr(module.os, "replace", lambda _source, _target: (_ for _ in ()).throw(OSError("stop")))

    with pytest.raises(OSError, match="stop"):
        journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))

    assert not tuple(journal.state_directory.glob("*.json"))
    assert not tuple(journal.state_directory.glob("*.tmp"))


@pytest.mark.parametrize(
    ("payload", "error"),
    [("{", "invalid JSON"), (json.dumps({"schemaVersion": 999}), "unsupported schema")],
)
def test_corrupt_or_unknown_journal_entries_are_rejected(
    tmp_path: Path, payload: str, error: str
) -> None:
    journal = _journal(tmp_path)
    journal.state_directory.mkdir(parents=True)
    (journal.state_directory / "001-source-tests.json").write_text(payload, encoding="utf-8")

    with pytest.raises(JournalCorruptionError, match=error):
        journal.entries()


def test_phase_order_rejects_skipped_and_repeated_successes(tmp_path: Path) -> None:
    journal = _journal(tmp_path)

    with pytest.raises(ValueError, match="expected source-tests"):
        journal.record("amd64-build", artifacts=(_marker(tmp_path, "wrong"),))

    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    with pytest.raises(ValueError, match="expected amd64-build"):
        journal.record("source-tests", artifacts=(_marker(tmp_path, "repeat"),))


def test_passing_phase_requires_digest_bearing_evidence_in_memory_and_on_disk(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    with pytest.raises(ValueError, match="artifact evidence"):
        journal.record("source-tests")

    entry = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = _payload(entry)
    payload["artifacts"] = []
    entry.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(JournalCorruptionError, match="invalid evidence"):
        journal.entries()


def test_existing_journal_rejects_a_corrupt_skipped_phase(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    source = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = _payload(source)
    payload["phase"] = "benchmark"
    (journal.state_directory / "002-benchmark.json").write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(JournalCorruptionError, match="phase order"):
        journal.entries()


def test_resume_reuses_only_digest_verified_local_and_remote_evidence(tmp_path: Path) -> None:
    expected_remote_digest = "sha256:" + "d" * 64
    seen: list[tuple[str, str]] = []

    def resolve(location: str, reference: str) -> str | None:
        seen.append((location, reference))
        return expected_remote_digest if location == "remote" else None

    journal = _journal(tmp_path, artifact_digest=resolve)
    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    journal.record(
        "amd64-build",
        artifacts=(ArtifactEvidence("remote", "registry.local/control@amd64", expected_remote_digest),),
    )

    assert journal.resume() == ResumePlan(("source-tests", "amd64-build"), "benchmark", ())
    assert seen == [("remote", "registry.local/control@amd64")]


def test_resume_invalidates_the_completed_suffix_in_one_atomic_boundary(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.write_text("original", encoding="utf-8")
    journal = _journal(tmp_path)
    journal.record("source-tests", artifacts=(_artifact(source),))
    journal.record("amd64-build", artifacts=(_marker(tmp_path, "amd64"),))
    journal.record("benchmark", artifacts=(_marker(tmp_path, "benchmark"),))
    source.write_text("changed", encoding="utf-8")

    resume = journal.resume()

    assert resume.reusable_phases == ()
    assert resume.restart_phase == "source-tests"
    assert resume.invalidated_phases == PHASES
    entries = journal.entries()
    assert len(entries) == 4
    assert entries[-1]["kind"] == "invalidation"
    assert entries[-1]["affectedPhases"] == list(PHASES)


def test_crash_before_atomic_invalidation_never_allows_stale_downstream_reuse(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "source"
    source.write_text("original", encoding="utf-8")
    journal = _journal(tmp_path)
    journal.record("source-tests", artifacts=(_artifact(source),))
    journal.record("amd64-build", artifacts=(_marker(tmp_path, "amd64"),))
    journal.record("benchmark", artifacts=(_marker(tmp_path, "benchmark"),))
    source.write_text("changed", encoding="utf-8")
    module = __import__("controlplane_tool.release.state", fromlist=["os"])
    original_replace = module.os.replace
    monkeypatch.setattr(module.os, "replace", lambda _source, _target: (_ for _ in ()).throw(OSError("crash")))

    with pytest.raises(OSError, match="crash"):
        journal.resume()
    monkeypatch.setattr(module.os, "replace", original_replace)

    assert journal.resume().invalidated_phases == PHASES
    journal.record("source-tests", artifacts=(_artifact(source),))
    assert journal.resume().reusable_phases == ("source-tests",)


def test_resume_rejects_identity_change_and_never_trusts_success_flag(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    changed = ReleaseIdentity("f" * 40, "0.18.0", "sha256:" + "b" * 64, "sha256:" + "c" * 64)

    resume = _journal(tmp_path, identity=changed).resume()

    assert resume.reusable_phases == ()
    assert resume.restart_phase == "source-tests"
    assert resume.invalidated_phases == ("source-tests",)


@pytest.mark.parametrize("version", ("v0.18.0", "../../escape", "0.18", "01.0.0"))
def test_release_identity_rejects_noncanonical_or_unsafe_versions(version: str) -> None:
    with pytest.raises(ResumeValidationError, match="version"):
        ReleaseIdentity("a" * 40, version, "sha256:" + "b" * 64, "sha256:" + "c" * 64)


def test_journal_has_no_arbitrary_metadata_channel_for_realistic_secret(tmp_path: Path) -> None:
    journal = _journal(tmp_path)

    with pytest.raises(TypeError, match="metadata"):
        journal.record(  # type: ignore[call-arg]
            "source-tests",
            artifacts=(_marker(tmp_path, "source"),),
            metadata={"evidence": "ghp_realisticTokenValueThatMustNotPersist"},  # type: ignore[reportCallIssue]
        )

    assert not tuple(journal.state_directory.glob("*.json"))


def test_disk_journal_rejects_an_arbitrary_metadata_field(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    entry = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = _payload(entry)
    payload["metadata"] = {"evidence": "ghp_realisticTokenValueThatMustNotPersist"}
    entry.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(JournalCorruptionError, match="invalid fields"):
        journal.entries()


def test_disk_journal_rejects_extra_artifact_key_on_entries_and_resume(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    entry = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = _payload(entry)
    payload["artifacts"][0]["token"] = "ghp_realisticTokenValueThatMustNotPersist"
    entry.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(JournalCorruptionError, match="invalid evidence"):
        journal.entries()
    with pytest.raises(JournalCorruptionError, match="invalid evidence"):
        journal.resume()


def test_disk_journal_rejects_extra_nested_release_key(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    entry = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = _payload(entry)
    payload["release"]["token"] = "ghp_realisticTokenValueThatMustNotPersist"
    entry.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(JournalCorruptionError, match="invalid evidence"):
        journal.entries()


def test_artifact_reference_rejects_known_fixture_secret() -> None:
    with pytest.raises(ValueError, match="fixture secrets"):
        ArtifactEvidence(
            "remote",
            "registry.local/fixture-ghcr-token-must-not-leak",
            "sha256:" + "d" * 64,
        )


def test_failed_phase_is_not_reusable_and_retries_same_phase(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    journal.record("source-tests", outcome="failed")

    assert journal.resume().restart_phase == "source-tests"
    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))


def test_missing_remote_evidence_invalidates_it_and_downstream(tmp_path: Path) -> None:
    journal = _journal(tmp_path, artifact_digest=lambda _location, _reference: None)
    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    journal.record(
        "amd64-build",
        artifacts=(ArtifactEvidence("remote", "registry.local/control@amd64", "sha256:" + "d" * 64),),
    )

    resume = journal.resume()

    assert resume.reusable_phases == ("source-tests",)
    assert resume.restart_phase == "amd64-build"
    assert resume.invalidated_phases == ("amd64-build",)


def test_journal_uses_exclusive_lock_and_fsyncs_file_and_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = __import__("controlplane_tool.release.state", fromlist=["fcntl", "os"])
    locks: list[int] = []
    syncs: list[int] = []
    original_flock = module.fcntl.flock
    original_fsync = module.os.fsync
    monkeypatch.setattr(module.fcntl, "flock", lambda descriptor, operation: locks.append(operation))
    monkeypatch.setattr(module.os, "fsync", lambda descriptor: syncs.append(descriptor))

    _journal(tmp_path).record("source-tests", artifacts=(_marker(tmp_path, "source"),))

    assert locks[0] == module.fcntl.LOCK_EX | module.fcntl.LOCK_NB
    assert locks[-1] == module.fcntl.LOCK_UN
    assert len(syncs) == 2
    monkeypatch.setattr(module.fcntl, "flock", original_flock)
    monkeypatch.setattr(module.os, "fsync", original_fsync)


def test_digest_path_is_stable_and_sha256_prefixed(tmp_path: Path) -> None:
    artifact = tmp_path / "evidence"
    artifact.write_bytes(b"evidence")

    assert digest_path(artifact) == "sha256:" + hashlib.sha256(b"evidence").hexdigest()


def test_invalid_artifact_evidence_is_rejected_before_persisting(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="digest"):
        ArtifactEvidence("local", "x", "wrong")
    with pytest.raises(ResumeValidationError, match="artifact location"):
        ArtifactEvidence("other", "x", "sha256:" + "d" * 64)
