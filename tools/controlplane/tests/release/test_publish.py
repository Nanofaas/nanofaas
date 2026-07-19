from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
from pathlib import Path

import pytest

from controlplane_tool.release import publish
from controlplane_tool.release.state import ArtifactEvidence


REPO_ROOT = Path(__file__).resolve().parents[4]
LOCAL_REGISTRY = "localhost:5000/nanofaas"
VERSION = "v0.18.0"


def _plan() -> publish.PublishPlan:
    return publish.build_publish_plan(
        REPO_ROOT,
        VERSION,
        local_registry=LOCAL_REGISTRY,
    )


def _source_digest(reference: str) -> str:
    return "sha256:" + hashlib.sha256(f"local:{reference}".encode()).hexdigest()


def _evidence(plan: publish.PublishPlan) -> tuple[ArtifactEvidence, ...]:
    return tuple(
        ArtifactEvidence("remote", f"docker://{copy.source}", _source_digest(copy.source))
        for copy in plan.copies
    )


@dataclass
class _Result:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""


@dataclass
class _PublishProvider:
    """Records commands; models GHCR state driven by skopeo/imagetools calls."""

    commands: list[tuple[str, ...]] = field(default_factory=list)
    ghcr_digests: dict[str, str] = field(default_factory=dict)
    copy_corruptions: set[str] = field(default_factory=set)
    retag_corruptions: set[str] = field(default_factory=set)
    fail_on_prefix: tuple[str, ...] | None = None
    inspect_platform_overrides: dict[str, tuple[str, ...]] = field(default_factory=dict)

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
        if self.fail_on_prefix is not None and argv[: len(self.fail_on_prefix)] == self.fail_on_prefix:
            return _Result(return_code=1)
        if argv[:2] == ("skopeo", "copy"):
            source = argv[-2].removeprefix("docker://")
            destination = argv[-1].removeprefix("docker://")
            digest = _source_digest(source)
            if destination in self.copy_corruptions:
                digest = "sha256:" + "f" * 64
            self.ghcr_digests[destination] = digest
            return _Result()
        if argv[:2] == ("skopeo", "inspect"):
            reference = argv[-1].removeprefix("docker://")
            digest = self.ghcr_digests.get(reference)
            if digest is None:
                return _Result(return_code=1)
            return _Result(stdout=f"{digest}\n")
        if argv[:4] == ("docker", "buildx", "imagetools", "create"):
            tag = argv[argv.index("--tag") + 1]
            sources = argv[argv.index("--tag") + 2 :]
            if len(sources) == 1 and "@sha256:" in sources[0]:
                # re-tagging a digest-pinned manifest preserves its digest
                digest = "sha256:" + sources[0].rsplit("@sha256:", 1)[1]
                if tag in self.retag_corruptions:
                    digest = "sha256:" + "e" * 64
                self.ghcr_digests[tag] = digest
            else:
                payload = ",".join(sorted(sources))
                self.ghcr_digests[tag] = (
                    "sha256:" + hashlib.sha256(f"manifest:{payload}".encode()).hexdigest()
                )
            return _Result()
        if argv[:4] == ("docker", "buildx", "imagetools", "inspect"):
            reference = argv[-1]
            if reference not in self.ghcr_digests:
                return _Result(return_code=1)
            platforms = self.inspect_platform_overrides.get(
                reference, ("linux/amd64", "linux/arm64")
            )
            lines = [f"Name:      {reference}", "Manifests:"]
            for platform in platforms:
                lines.append(f"  Platform:    {platform}")
            return _Result(stdout="\n".join(lines) + "\n")
        return _Result()


# --- plan derivation -------------------------------------------------------


def test_publish_plan_covers_the_full_52_cell_matrix() -> None:
    plan = _plan()

    assert len(plan.copies) == 52
    assert len(plan.manifests) == 26
    assert len(plan.aliases) == 8
    assert plan.repository == publish.GHCR_REPOSITORY
    # AMD64 architecture uploads strictly precede ARM64 ones
    architectures = [
        "arm64" if "-arm64" in copy.source.rsplit(":", 1)[1] else "amd64"
        for copy in plan.copies
    ]
    assert architectures == sorted(architectures)
    rendered = " ".join(
        [copy.destination for copy in plan.copies]
        + [manifest.reference for manifest in plan.manifests]
        + [alias.reference for alias in plan.aliases]
    )
    assert ":latest" not in rendered
    assert "localhost:5000" not in rendered


def test_control_plane_and_watchdog_follow_the_tag_policy() -> None:
    plan = _plan()
    ghcr = publish.GHCR_REPOSITORY

    control_plane_copies = {
        copy.destination for copy in plan.copies if copy.target == "control-plane"
    }
    assert control_plane_copies == {
        f"{ghcr}/control-plane:{VERSION}-amd64-jvm",
        f"{ghcr}/control-plane:{VERSION}-amd64-native",
        f"{ghcr}/control-plane:{VERSION}-arm64-jvm",
        f"{ghcr}/control-plane:{VERSION}-arm64-native",
    }
    control_plane_manifests = {
        manifest.reference: manifest.sources
        for manifest in plan.manifests
        if manifest.target == "control-plane"
    }
    assert control_plane_manifests == {
        f"{ghcr}/control-plane:{VERSION}-jvm": (
            f"{ghcr}/control-plane:{VERSION}-amd64-jvm",
            f"{ghcr}/control-plane:{VERSION}-arm64-jvm",
        ),
        f"{ghcr}/control-plane:{VERSION}-native": (
            f"{ghcr}/control-plane:{VERSION}-amd64-native",
            f"{ghcr}/control-plane:{VERSION}-arm64-native",
        ),
    }
    control_plane_alias = next(
        alias for alias in plan.aliases if alias.target == "control-plane"
    )
    assert control_plane_alias.reference == f"{ghcr}/control-plane:{VERSION}"
    assert control_plane_alias.source == f"{ghcr}/control-plane:{VERSION}-native"

    watchdog_copies = {copy.destination for copy in plan.copies if copy.target == "watchdog"}
    assert watchdog_copies == {
        f"{ghcr}/watchdog:{VERSION}-amd64",
        f"{ghcr}/watchdog:{VERSION}-arm64",
    }
    watchdog_manifest = next(
        manifest for manifest in plan.manifests if manifest.target == "watchdog"
    )
    assert watchdog_manifest.reference == f"{ghcr}/watchdog:{VERSION}"
    assert not any(alias.target == "watchdog" for alias in plan.aliases)


def test_every_alias_points_at_a_native_manifest() -> None:
    plan = _plan()
    manifest_references = {manifest.reference for manifest in plan.manifests}
    for alias in plan.aliases:
        assert alias.source.endswith("-native")
        assert alias.source in manifest_references


# --- evidence gate ---------------------------------------------------------


def test_publication_requires_digest_evidence_for_all_52_cells() -> None:
    plan = _plan()
    complete = _evidence(plan)

    digests = publish.require_publication_evidence(plan, complete)
    assert set(digests) == {copy.source for copy in plan.copies}

    with pytest.raises(RuntimeError, match="52"):
        publish.require_publication_evidence(plan, complete[:-1])


# --- architecture copies ---------------------------------------------------


def test_architecture_copies_preserve_digests_and_use_the_authfile() -> None:
    plan = _plan()
    provider = _PublishProvider()
    digests = publish.require_publication_evidence(plan, _evidence(plan))

    evidence = publish.publish_architecture_images(
        provider,
        object(),
        plan,
        digests,
        authfile="/tmp/creds/docker/config.json",
    )

    copies = [command for command in provider.commands if command[:2] == ("skopeo", "copy")]
    assert len(copies) == 52
    for command in copies:
        assert "--preserve-digests" in command
        assert "--src-tls-verify=false" in command
        assert "--dest-authfile=/tmp/creds/docker/config.json" in command
    assert len(evidence) == 52
    assert all(artifact.location == "remote" for artifact in evidence)


def test_copy_digest_mismatch_stops_before_any_manifest() -> None:
    plan = _plan()
    corrupted = plan.copies[3].destination
    provider = _PublishProvider(copy_corruptions={corrupted})
    digests = publish.require_publication_evidence(plan, _evidence(plan))

    with pytest.raises(RuntimeError, match="digest"):
        publish.publish_architecture_images(
            provider,
            object(),
            plan,
            digests,
            authfile="/tmp/creds/docker/config.json",
        )

    assert not any(
        command[:4] == ("docker", "buildx", "imagetools", "create")
        for command in provider.commands
    )


def test_copy_command_failure_stops_the_phase() -> None:
    plan = _plan()
    provider = _PublishProvider(fail_on_prefix=("skopeo", "copy"))
    digests = publish.require_publication_evidence(plan, _evidence(plan))

    with pytest.raises(RuntimeError):
        publish.publish_architecture_images(
            provider,
            object(),
            plan,
            digests,
            authfile="/tmp/creds/docker/config.json",
        )
    assert not any(
        command[:4] == ("docker", "buildx", "imagetools", "create")
        for command in provider.commands
    )


# --- manifests -------------------------------------------------------------


def _published(provider: _PublishProvider, plan: publish.PublishPlan) -> dict[str, str]:
    digests = publish.require_publication_evidence(plan, _evidence(plan))
    evidence = publish.publish_architecture_images(
        provider,
        object(),
        plan,
        digests,
        authfile="/tmp/creds/docker/config.json",
    )
    return {
        artifact.reference.removeprefix("docker://"): artifact.digest
        for artifact in evidence
    }


def test_manifests_require_exactly_amd64_and_arm64() -> None:
    plan = _plan()
    provider = _PublishProvider()
    architecture_digests = _published(provider, plan)

    evidence = publish.publish_manifests(
        provider,
        object(),
        plan,
        architecture_digests,
        docker_config="/tmp/creds/docker",
    )

    creations = [
        command
        for command in provider.commands
        if command[:4] == ("docker", "buildx", "imagetools", "create")
    ]
    assert len(creations) == 26
    assert len(evidence) == 26


def test_attestation_manifest_rows_are_tolerated_but_missing_arm64_is_not() -> None:
    plan = _plan()
    provider = _PublishProvider()
    architecture_digests = _published(provider, plan)
    first = plan.manifests[0].reference
    provider.inspect_platform_overrides[first] = ("linux/amd64", "unknown/unknown")

    with pytest.raises(RuntimeError, match="arm64"):
        publish.publish_manifests(
            provider,
            object(),
            plan,
            architecture_digests,
            docker_config="/tmp/creds/docker",
        )


def test_manifest_failure_prevents_every_alias() -> None:
    plan = _plan()
    provider = _PublishProvider(fail_on_prefix=("docker", "buildx", "imagetools", "create"))
    architecture_digests = _published(provider, plan)

    with pytest.raises(RuntimeError):
        publish.publish_manifests(
            provider,
            object(),
            plan,
            architecture_digests,
            docker_config="/tmp/creds/docker",
        )

    alias_references = {alias.reference for alias in plan.aliases}
    created = {
        command[command.index("--tag") + 1]
        for command in provider.commands
        if command[:4] == ("docker", "buildx", "imagetools", "create")
        and "--tag" in command
    }
    assert created.isdisjoint(alias_references)


# --- aliases ---------------------------------------------------------------


def test_aliases_are_created_last_and_verified_against_their_manifest() -> None:
    plan = _plan()
    provider = _PublishProvider()
    architecture_digests = _published(provider, plan)
    manifest_evidence = publish.publish_manifests(
        provider,
        object(),
        plan,
        architecture_digests,
        docker_config="/tmp/creds/docker",
    )
    manifest_digests = {
        artifact.reference.removeprefix("docker://"): artifact.digest
        for artifact in manifest_evidence
    }

    evidence = publish.publish_aliases(
        provider,
        object(),
        plan,
        manifest_digests,
        docker_config="/tmp/creds/docker",
    )

    assert len(evidence) == 8
    for alias in plan.aliases:
        assert provider.ghcr_digests[alias.reference] == provider.ghcr_digests[alias.source]


def test_alias_digest_mismatch_fails_the_release() -> None:
    plan = _plan()
    provider = _PublishProvider()
    architecture_digests = _published(provider, plan)
    manifest_evidence = publish.publish_manifests(
        provider,
        object(),
        plan,
        architecture_digests,
        docker_config="/tmp/creds/docker",
    )
    manifest_digests = {
        artifact.reference.removeprefix("docker://"): artifact.digest
        for artifact in manifest_evidence
    }
    # the registry misbehaves: the created alias does not match its source
    provider.retag_corruptions.add(plan.aliases[0].reference)

    with pytest.raises(RuntimeError, match="alias"):
        publish.publish_aliases(
            provider,
            object(),
            plan,
            manifest_digests,
            docker_config="/tmp/creds/docker",
        )
