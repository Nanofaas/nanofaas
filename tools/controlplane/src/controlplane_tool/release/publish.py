"""GHCR promotion and manifest creation for a verified Azure release.

Publication order is deliberately non-transactional but safe: immutable
architecture tags are uploaded first, multi-architecture version manifests are
created and verified next, and mutable aliases are changed last. Any failure
leaves earlier immutable uploads in place and is safe to rerun.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.release.remote_retry import retry_on_connection_death
from controlplane_tool.images.plan import ImageCell, build_image_plan
from controlplane_tool.release.versioning import normalize_version
from controlplane_tool.release.state import ArtifactEvidence


PUBLISH_PHASES = ("publish-architectures", "publish-manifests", "publish-aliases")
GHCR_REPOSITORY = "ghcr.io/miciav/nanofaas"
_REQUIRED_PLATFORMS = frozenset({"linux/amd64", "linux/arm64"})
# Provenance/attestation manifest rows report this pseudo-platform.
_ATTESTATION_PLATFORM = "unknown/unknown"


@dataclass(frozen=True, slots=True)
class ArchitectureCopy:
    target: str
    source: str
    destination: str


@dataclass(frozen=True, slots=True)
class ManifestSpec:
    target: str
    reference: str
    sources: tuple[str, str]


@dataclass(frozen=True, slots=True)
class AliasSpec:
    target: str
    reference: str
    source: str


@dataclass(frozen=True, slots=True)
class PublishPlan:
    version: str
    repository: str
    copies: tuple[ArchitectureCopy, ...]
    manifests: tuple[ManifestSpec, ...]
    aliases: tuple[AliasSpec, ...]


def ghcr_username(repository: str = GHCR_REPOSITORY) -> str:
    parts = repository.split("/")
    if len(parts) < 2 or parts[0] != "ghcr.io" or not parts[1]:
        raise ValueError(f"invalid GHCR repository: {repository}")
    return parts[1]


def build_publish_plan(
    repo_root: Path,
    version: str,
    *,
    local_registry: str,
    repository: str = GHCR_REPOSITORY,
) -> PublishPlan:
    # architecture cells are v-prefixed by build_image_plan: manifests and
    # aliases must use the same normalized tag scheme
    _, version_tag = normalize_version(version)
    full = build_image_plan(
        repo_root,
        version_tag,
        registry=local_registry,
        architectures=("amd64", "arm64"),
    )

    def destination(cell: ImageCell) -> str:
        name, tag = cell.image.rsplit("/", 1)[-1].split(":", 1)
        del name
        return f"{repository}/{cell.target.name}:{tag}"

    copies = tuple(
        ArchitectureCopy(
            target=cell.target.name,
            source=cell.image,
            destination=destination(cell),
        )
        for cell in full.cells
    )

    grouped: dict[tuple[str, str], dict[str, str]] = {}
    for cell in full.cells:
        grouped.setdefault((cell.target.name, cell.flavor), {})[cell.architecture] = destination(
            cell
        )

    manifests: list[ManifestSpec] = []
    aliases: list[AliasSpec] = []
    for (target_name, flavor), by_architecture in grouped.items():
        if set(by_architecture) != {"amd64", "arm64"}:
            raise ValueError(
                f"publication requires both architectures for {target_name} ({flavor})"
            )
        manifest_tag = version_tag if flavor == "default" else f"{version_tag}-{flavor}"
        manifests.append(
            ManifestSpec(
                target=target_name,
                reference=f"{repository}/{target_name}:{manifest_tag}",
                sources=(by_architecture["amd64"], by_architecture["arm64"]),
            )
        )
        if flavor == "native":
            aliases.append(
                AliasSpec(
                    target=target_name,
                    reference=f"{repository}/{target_name}:{version_tag}",
                    source=f"{repository}/{target_name}:{manifest_tag}",
                )
            )
    return PublishPlan(
        version=version_tag,
        repository=repository,
        copies=copies,
        manifests=tuple(manifests),
        aliases=tuple(aliases),
    )


def require_publication_evidence(
    plan: PublishPlan,
    artifacts: Iterable[ArtifactEvidence],
) -> dict[str, str]:
    """Map every architecture source to its verified local-registry digest."""
    digests = {
        artifact.reference.removeprefix("docker://"): artifact.digest
        for artifact in artifacts
        if artifact.location == "remote" and artifact.reference.startswith("docker://")
    }
    missing = tuple(copy.source for copy in plan.copies if copy.source not in digests)
    if missing:
        raise RuntimeError(
            f"publication requires digest evidence for all {len(plan.copies)} cells; "
            f"missing {len(missing)} (first: {missing[0]})"
        )
    return {copy.source: digests[copy.source] for copy in plan.copies}


def publish_architecture_images(
    provider: object,
    request: object,
    plan: PublishPlan,
    source_digests: Mapping[str, str],
    *,
    authfile: str,
) -> tuple[ArtifactEvidence, ...]:
    """Copy immutable architecture tags to GHCR, digest-verified one by one."""
    evidence: list[ArtifactEvidence] = []
    for copy in plan.copies:
        _exec(
            provider,
            request,
            (
                "skopeo",
                "copy",
                "--preserve-digests",
                "--src-tls-verify=false",
                f"--dest-authfile={authfile}",
                f"docker://{copy.source}",
                f"docker://{copy.destination}",
            ),
        )
        published = _registry_digest(provider, request, copy.destination, authfile=authfile)
        expected = source_digests[copy.source]
        if published != expected:
            raise RuntimeError(
                "published digest mismatch for "
                f"{copy.destination}: expected {expected}, got {published}"
            )
        evidence.append(ArtifactEvidence("remote", f"docker://{copy.destination}", published))
    return tuple(evidence)


def publish_manifests(
    provider: object,
    request: object,
    plan: PublishPlan,
    architecture_digests: Mapping[str, str],
    *,
    docker_config: str,
) -> tuple[ArtifactEvidence, ...]:
    """Create and verify multi-architecture version manifests.

    Sources are digest-pinned from the verified architecture uploads, never
    mutable tags: a repointed tag between phases cannot reach a manifest.
    """
    evidence: list[ArtifactEvidence] = []
    for manifest in plan.manifests:
        pinned_sources = tuple(
            _pin(source, architecture_digests) for source in manifest.sources
        )
        _exec(
            provider,
            request,
            (
                "docker",
                "buildx",
                "imagetools",
                "create",
                "--tag",
                manifest.reference,
                *pinned_sources,
            ),
            env={"DOCKER_CONFIG": docker_config},
        )
        inspection = _exec(
            provider,
            request,
            ("docker", "buildx", "imagetools", "inspect", manifest.reference),
            env={"DOCKER_CONFIG": docker_config},
        )
        require_dual_architecture(str(getattr(inspection, "stdout", "")), manifest.reference)
        digest = _registry_digest(
            provider,
            request,
            manifest.reference,
            authfile=f"{docker_config}/config.json",
        )
        evidence.append(ArtifactEvidence("remote", f"docker://{manifest.reference}", digest))
    return tuple(evidence)


def publish_aliases(
    provider: object,
    request: object,
    plan: PublishPlan,
    manifest_digests: Mapping[str, str],
    *,
    docker_config: str,
) -> tuple[ArtifactEvidence, ...]:
    """Point mutable version aliases at their verified native manifests, last."""
    evidence: list[ArtifactEvidence] = []
    for alias in plan.aliases:
        expected = manifest_digests.get(alias.source)
        if expected is None:
            raise RuntimeError(f"alias source manifest has no verified digest: {alias.source}")
        _exec(
            provider,
            request,
            (
                "docker",
                "buildx",
                "imagetools",
                "create",
                "--tag",
                alias.reference,
                _pin(alias.source, manifest_digests),
            ),
            env={"DOCKER_CONFIG": docker_config},
        )
        published = _registry_digest(
            provider,
            request,
            alias.reference,
            authfile=f"{docker_config}/config.json",
        )
        if published != expected:
            raise RuntimeError(
                f"alias digest mismatch for {alias.reference}: "
                f"expected {expected}, got {published}"
            )
        evidence.append(ArtifactEvidence("remote", f"docker://{alias.reference}", published))
    return tuple(evidence)


def _pin(reference: str, digests: Mapping[str, str]) -> str:
    digest = digests.get(reference)
    if digest is None:
        raise RuntimeError(f"no verified digest for publication source: {reference}")
    return f"{reference.rsplit(':', 1)[0]}@{digest}"


def require_dual_architecture(output: str, reference: str) -> None:
    platforms = {
        line.split(":", 1)[1].strip()
        for line in output.splitlines()
        if line.strip().startswith("Platform:")
    }
    platforms.discard(_ATTESTATION_PLATFORM)
    if platforms != _REQUIRED_PLATFORMS:
        raise RuntimeError(
            f"manifest {reference} must contain exactly linux/amd64 and linux/arm64; "
            f"found: {', '.join(sorted(platforms)) or 'none'}"
        )


def _registry_digest(
    provider: object,
    request: object,
    reference: str,
    *,
    authfile: str,
) -> str:
    result = _exec(
        provider,
        request,
        (
            "skopeo",
            "inspect",
            f"--authfile={authfile}",
            "--format={{.Digest}}",
            f"docker://{reference}",
        ),
    )
    digest = str(getattr(result, "stdout", "")).strip()
    if not digest.startswith("sha256:") or len(digest) != 71:
        raise RuntimeError(f"invalid published digest for {reference}")
    return digest


def _exec(
    provider: object,
    request: object,
    argv: tuple[str, ...],
    *,
    env: dict[str, str] | None = None,
) -> object:
    result = retry_on_connection_death(
        lambda: provider.exec_argv(  # type: ignore[attr-defined]
            request, argv, env=env, cwd=None, dry_run=False
        ),
        describe=f"publication {argv[0]}",
    )
    if int(getattr(result, "return_code", 0)) != 0:
        raise RuntimeError(f"release publication command failed: {argv[0]} {argv[1]}")
    return result
