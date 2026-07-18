from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from controlplane_tool.release.state import (
    ArtifactEvidence,
    JournalCorruptionError,
    ReleaseIdentity,
    ReleaseJournal,
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


def _journal(tmp_path: Path, **changes: object) -> ReleaseJournal:
    identity = changes.pop("identity", _identity())
    return ReleaseJournal(tmp_path / "runs", identity, phases=PHASES, **changes)


def _artifact(path: Path) -> ArtifactEvidence:
    return ArtifactEvidence("local", str(path), digest_path(path))


def _marker(tmp_path: Path, name: str) -> ArtifactEvidence:
    path = tmp_path / name
    path.write_text(name, encoding="utf-8")
    return _artifact(path)


def test_entry_is_atomic_append_only_and_contains_release_evidence(tmp_path: Path) -> None:
    artifact = tmp_path / "source.tar"
    artifact.write_text("source", encoding="utf-8")
    journal = _journal(tmp_path)

    entry = journal.record("source-tests", artifacts=(_artifact(artifact),))

    assert entry.name == "001-source-tests.json"
    assert journal.state_directory == tmp_path / "runs/releases/0.18.0/state"
    assert not tuple(journal.state_directory.glob("*.tmp"))
    payload = json.loads(entry.read_text(encoding="utf-8"))
    assert payload == {
        "artifacts": [
            {
                "digest": digest_path(artifact),
                "location": "local",
                "reference": str(artifact),
            }
        ],
        "completedAt": payload["completedAt"],
        "outcome": "passed",
        "phase": "source-tests",
        "release": {
            "environmentDigest": "sha256:" + "c" * 64,
            "preparedVersion": "0.18.0",
            "releaseConfigDigest": "sha256:" + "b" * 64,
            "sourceCommit": "a" * 40,
        },
        "schemaVersion": 1,
        "startedAt": payload["startedAt"],
    }


def test_interrupted_atomic_write_never_creates_a_visible_entry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    journal = _journal(tmp_path)
    module = __import__("controlplane_tool.release.state", fromlist=["os"])

    def interrupted_replace(source: str, destination: str) -> None:
        raise OSError("interrupted write")

    monkeypatch.setattr(module.os, "replace", interrupted_replace)

    with pytest.raises(OSError, match="interrupted"):
        journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))

    assert not tuple(journal.state_directory.glob("*.json"))
    assert not tuple(journal.state_directory.glob("*.tmp"))


@pytest.mark.parametrize(
    ("payload", "error"),
    [
        ("{", "invalid JSON"),
        (json.dumps({"schemaVersion": 999}), "unsupported schema"),
    ],
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
        journal.record("amd64-build")

    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    with pytest.raises(ValueError, match="expected amd64-build"):
        journal.record("source-tests")


def test_passing_phase_requires_digest_bearing_evidence(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="artifact evidence"):
        _journal(tmp_path).record("source-tests")


def test_existing_journal_rejects_a_corrupt_skipped_phase(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    source = journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    payload = json.loads(source.read_text(encoding="utf-8"))
    payload["phase"] = "benchmark"
    (journal.state_directory / "002-benchmark.json").write_text(
        json.dumps(payload), encoding="utf-8"
    )

    with pytest.raises(JournalCorruptionError, match="phase order"):
        journal.entries()


def test_resume_reuses_only_digest_verified_local_and_remote_evidence(tmp_path: Path) -> None:
    artifact = tmp_path / "source.tar"
    artifact.write_text("source", encoding="utf-8")
    expected_remote_digest = "sha256:" + "d" * 64
    seen: list[tuple[str, str]] = []

    def resolve(location: str, reference: str) -> str | None:
        seen.append((location, reference))
        if location == "remote":
            return expected_remote_digest
        return None

    journal = _journal(tmp_path, artifact_digest=resolve)
    journal.record("source-tests", artifacts=(_artifact(artifact),))
    journal.record(
        "amd64-build",
        artifacts=(ArtifactEvidence("remote", "registry.local/control@amd64", expected_remote_digest),),
    )

    resume = journal.resume()

    assert resume.reusable_phases == ("source-tests", "amd64-build")
    assert resume.restart_phase == "benchmark"
    assert resume.invalidated_phases == ()
    assert seen == [("remote", "registry.local/control@amd64")]


def test_resume_invalidates_earliest_bad_phase_and_all_downstream(tmp_path: Path) -> None:
    artifact = tmp_path / "source.tar"
    artifact.write_text("source", encoding="utf-8")
    journal = _journal(tmp_path)
    journal.record("source-tests", artifacts=(_artifact(artifact),))
    journal.record("amd64-build", artifacts=(_marker(tmp_path, "amd64"),))
    journal.record("benchmark", artifacts=(_marker(tmp_path, "benchmark"),))
    artifact.write_text("different", encoding="utf-8")

    resume = journal.resume()

    assert resume.reusable_phases == ()
    assert resume.restart_phase == "source-tests"
    assert resume.invalidated_phases == PHASES
    assert [entry["outcome"] for entry in journal.entries()][-3:] == [
        "invalidated",
        "invalidated",
        "invalidated",
    ]
    assert [entry["phase"] for entry in journal.entries()][-3:] == list(PHASES)


def test_resume_rejects_identity_change_and_does_not_trust_success_flag(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    journal.record("source-tests", artifacts=(_marker(tmp_path, "source"),))
    changed = ReleaseIdentity(
        source_commit="f" * 40,
        prepared_version="0.18.0",
        release_config_digest="sha256:" + "b" * 64,
        environment_digest="sha256:" + "c" * 64,
    )

    resume = _journal(tmp_path, identity=changed).resume()

    assert resume.reusable_phases == ()
    assert resume.restart_phase == "source-tests"
    assert resume.invalidated_phases == ("source-tests",)


@pytest.mark.parametrize(
    "secret",
    (
        "fixture-secret-must-not-leak",
        "fixture-ghcr-token-must-not-leak",
        "fixture-cosign-key-must-not-leak",
        "fixture-cosign-password-must-not-leak",
    ),
)
def test_journal_rejects_credentials_and_known_fixture_secret_content(
    tmp_path: Path, secret: str
) -> None:
    journal = _journal(tmp_path)

    with pytest.raises(ValueError, match="sensitive"):
        journal.record("source-tests", metadata={"token": "not-allowed"})
    with pytest.raises(ValueError, match="sensitive"):
        journal.record(
            "source-tests",
            metadata={"evidence": secret},
        )

    assert not tuple(journal.state_directory.glob("*.json"))


def test_failed_phase_is_not_reusable_and_retries_same_phase(tmp_path: Path) -> None:
    journal = _journal(tmp_path)
    journal.record("source-tests", outcome="failed")

    resume = journal.resume()

    assert resume.reusable_phases == ()
    assert resume.restart_phase == "source-tests"
    assert resume.invalidated_phases == ()
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


def test_digest_path_is_stable_and_sha256_prefixed(tmp_path: Path) -> None:
    artifact = tmp_path / "evidence"
    artifact.write_bytes(b"evidence")

    assert digest_path(artifact) == "sha256:" + hashlib.sha256(b"evidence").hexdigest()


def test_invalid_artifact_evidence_is_rejected_before_persisting(tmp_path: Path) -> None:
    journal = _journal(tmp_path)

    with pytest.raises(ValueError, match="digest"):
        journal.record("source-tests", artifacts=(ArtifactEvidence("local", "x", "wrong"),))

    with pytest.raises(ResumeValidationError, match="artifact location"):
        ArtifactEvidence("other", "x", "sha256:" + "d" * 64)
