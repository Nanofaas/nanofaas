from __future__ import annotations

from dataclasses import dataclass, field
import json
from pathlib import Path

import pytest

from controlplane_tool.release import attest
from controlplane_tool.release.secrets import RemoteCosignCredentials
from controlplane_tool.release.state import ReleaseIdentity, ReleaseJournal


FIXTURE_PASSWORD = "fixture-cosign-password"
PROFILE = "azure-d8s-v5+d2s-v5-amd64-native-loadtest-v1"


def _record() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "version": "v0.18.0",
        "sourceCommit": "a" * 40,
        "imageDigests": {"ghcr.io/miciav/nanofaas/control-plane:v0.18.0-amd64-native": "sha256:" + "1" * 64},
        "profile": {"name": PROFILE},
        "runCount": 3,
        "thresholds": {},
        "aggregates": {
            "throughputRps": 100.0,
            "errorRate": 0.0,
            "latencyP95Ms": 12.0,
            "peakReplicas": 4.0,
        },
    }


def _images() -> dict[str, str]:
    return {
        "ghcr.io/miciav/nanofaas/control-plane:v0.18.0-amd64-native": "sha256:" + "1" * 64,
        "ghcr.io/miciav/nanofaas/control-plane:v0.18.0-native": "sha256:" + "2" * 64,
    }


@dataclass
class _Result:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""


@dataclass
class _AttestProvider:
    commands: list[tuple[str, ...]] = field(default_factory=list)
    fail_on_contains: str | None = None

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _Result:
        del request, env, cwd, dry_run
        self.commands.append(argv)
        if self.fail_on_contains is not None and any(
            self.fail_on_contains in part for part in argv
        ):
            return _Result(return_code=1)
        return _Result()

    def transfer_to(self, request: object, *, source: Path, destination: str) -> _Result:
        del request, source, destination
        return _Result()


def _cosign() -> RemoteCosignCredentials:
    return RemoteCosignCredentials(
        key_file="/tmp/nanofaas-release-credentials.fake01/cosign-key",
        password_file="/tmp/nanofaas-release-credentials.fake01/cosign-password",
    )


# --- pinned toolchain ------------------------------------------------------


def test_attestation_toolchain_images_are_digest_pinned() -> None:
    for image in (attest.SYFT_IMAGE, attest.COSIGN_IMAGE):
        assert "@sha256:" in image
        assert len(image.rsplit("@sha256:", 1)[1]) == 64
        assert ":latest" not in image


# --- predicate -------------------------------------------------------------


def test_release_predicate_contains_the_required_evidence() -> None:
    predicate = attest.build_release_predicate(
        version="v0.18.0",
        source_commit="a" * 40,
        azure_profile=PROFILE,
        benchmark_record_digest="sha256:" + "3" * 64,
        image_digests=_images(),
    )

    assert predicate["schemaVersion"] == 1
    assert predicate["sourceCommit"] == "a" * 40
    assert predicate["azureProfile"] == PROFILE
    assert predicate["benchmarkRecordDigest"] == "sha256:" + "3" * 64
    assert predicate["imageDigests"] == _images()

    rendered = attest.render_predicate(predicate)
    assert rendered == attest.render_predicate(json.loads(rendered))


def test_predicate_rejects_missing_or_invalid_digests() -> None:
    with pytest.raises(ValueError):
        attest.build_release_predicate(
            version="v0.18.0",
            source_commit="a" * 40,
            azure_profile=PROFILE,
            benchmark_record_digest="not-a-digest",
            image_digests=_images(),
        )
    with pytest.raises(ValueError):
        attest.build_release_predicate(
            version="v0.18.0",
            source_commit="a" * 40,
            azure_profile=PROFILE,
            benchmark_record_digest="sha256:" + "3" * 64,
            image_digests={},
        )


# --- remote signing --------------------------------------------------------


def test_sbom_sign_attest_and_verify_never_expose_the_password() -> None:
    provider = _AttestProvider()

    attest.attest_release_images(
        provider,
        object(),
        images=_images(),
        predicate_remote="/srv/release/predicate.json",
        sbom_dir_remote="/srv/release/sboms",
        cosign=_cosign(),
        docker_config="/tmp/nanofaas-release-credentials.fake01/docker",
    )

    rendered = "\n".join(" ".join(command) for command in provider.commands)
    assert FIXTURE_PASSWORD not in rendered
    assert "cosign-password" in rendered  # only the file path travels
    syft_runs = [c for c in provider.commands if attest.SYFT_IMAGE in c]
    cosign_runs = [c for c in provider.commands if attest.COSIGN_IMAGE in c]
    assert len(syft_runs) == len(_images())
    assert cosign_runs
    for command in cosign_runs:
        joined = " ".join(command)
        # the password value is read inside the remote shell, never in argv
        assert 'COSIGN_PASSWORD="$(cat' in joined
        assert "--env COSIGN_PASSWORD " in joined + " "
        assert "COSIGN_PASSWORD=" + FIXTURE_PASSWORD not in joined
    # every reference is signed by digest, not by mutable tag
    signs = [c for c in cosign_runs if "sign" in c]
    assert signs
    for command in signs:
        reference = command[-1]
        assert "@sha256:" in reference


def test_signature_verification_happens_after_signing_and_uses_the_key_file() -> None:
    provider = _AttestProvider()

    attest.attest_release_images(
        provider,
        object(),
        images=_images(),
        predicate_remote="/srv/release/predicate.json",
        sbom_dir_remote="/srv/release/sboms",
        cosign=_cosign(),
        docker_config="/tmp/nanofaas-release-credentials.fake01/docker",
    )

    rendered = [" ".join(command) for command in provider.commands]
    sign_indexes = [i for i, line in enumerate(rendered) if "cosign" in line and " sign" in line]
    verify_indexes = [i for i, line in enumerate(rendered) if "verify" in line]
    assert verify_indexes
    assert max(sign_indexes) < min(verify_indexes)


def test_signing_failure_stops_before_any_verification() -> None:
    provider = _AttestProvider(fail_on_contains="sign")

    with pytest.raises(RuntimeError):
        attest.attest_release_images(
            provider,
            object(),
            images=_images(),
            predicate_remote="/srv/release/predicate.json",
            sbom_dir_remote="/srv/release/sboms",
            cosign=_cosign(),
            docker_config="/tmp/nanofaas-release-credentials.fake01/docker",
        )

    assert not any("verify" in " ".join(command) for command in provider.commands)


# --- finalization ----------------------------------------------------------


def _journal(tmp_path: Path) -> ReleaseJournal:
    identity = ReleaseIdentity(
        source_commit="a" * 40,
        prepared_version="0.18.0",
        release_config_digest="sha256:" + "4" * 64,
        environment_digest="sha256:" + "5" * 64,
    )
    return ReleaseJournal(tmp_path, identity, phases=("finalize",))


def test_finalize_writes_records_then_appends_the_final_journal_entry(tmp_path: Path) -> None:
    docs = tmp_path / "performance"
    journal = _journal(tmp_path / "journal")

    evidence = attest.finalize_release(
        journal,
        record=_record(),
        performance_root=docs,
    )

    release_file = docs / "releases" / "v0.18.0.json"
    history = docs / "history.md"
    assert json.loads(release_file.read_text(encoding="utf-8"))["version"] == "v0.18.0"
    assert "v0.18.0" in history.read_text(encoding="utf-8")
    phases = [entry.get("phase") for entry in journal.entries()]
    assert "finalize" in phases
    references = {artifact.reference for artifact in evidence}
    assert str(release_file) in references


def test_history_regeneration_includes_previous_release_records(tmp_path: Path) -> None:
    docs = tmp_path / "performance"
    (docs / "releases").mkdir(parents=True)
    older = dict(_record(), version="v0.17.0")
    (docs / "releases" / "v0.17.0.json").write_text(
        json.dumps(older), encoding="utf-8"
    )

    attest.finalize_release(
        _journal(tmp_path / "journal"),
        record=_record(),
        performance_root=docs,
    )

    history = (docs / "history.md").read_text(encoding="utf-8")
    assert "v0.17.0" in history
    assert "v0.18.0" in history


def test_documentation_failure_leaves_no_final_journal_entry(tmp_path: Path) -> None:
    docs = tmp_path / "performance"
    journal = _journal(tmp_path / "journal")
    (docs / "releases").mkdir(parents=True)
    # history.md is a directory: regeneration must fail after the record write
    (docs / "history.md").mkdir()

    with pytest.raises(OSError):
        attest.finalize_release(journal, record=_record(), performance_root=docs)

    assert not any(entry.get("phase") == "finalize" for entry in journal.entries())


def test_finalize_retries_after_a_documentation_failure_without_rebuilding(
    tmp_path: Path,
) -> None:
    docs = tmp_path / "performance"
    journal = _journal(tmp_path / "journal")
    (docs / "releases").mkdir(parents=True)
    blocker = docs / "history.md"
    blocker.mkdir()

    with pytest.raises(OSError):
        attest.finalize_release(journal, record=_record(), performance_root=docs)
    assert not any(entry.get("phase") == "finalize" for entry in journal.entries())

    blocker.rmdir()
    evidence = attest.finalize_release(journal, record=_record(), performance_root=docs)

    assert any(entry.get("phase") == "finalize" for entry in journal.entries())
    assert (docs / "history.md").is_file()
    assert evidence
