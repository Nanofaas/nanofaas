"""SBOM generation, signing, and final release records.

The signing key and password reach the stack VM only for this window (staged
by `stage_cosign_credentials`, always cleaned up). The password is read inside
the remote shell from its staged file and exported through the environment;
it never appears in argv, task metadata, or logs. Published performance
history changes only after every signature and attestation has verified.
"""

from __future__ import annotations

from collections.abc import Mapping
import json
from pathlib import Path
import re
from typing import Any

from controlplane_tool.release.remote_retry import retry_on_connection_death
from controlplane_tool.release.metrics import render_history, render_release_record
from controlplane_tool.release.secrets import RemoteCosignCredentials
from controlplane_tool.release.state import ArtifactEvidence, ReleaseJournal, digest_path


ATTEST_PHASES = ("attest", "finalize")
SYFT_IMAGE = (
    "anchore/syft@sha256:"
    "f94e5d9fce1f2278491a8e3a63bd5f6ddb81fdfdbb8bf7a1637565c1d5344357"
)
COSIGN_IMAGE = (
    "gcr.io/projectsigstore/cosign@sha256:"
    "f1946d0f30fc8e3777b02f2201e02efdba9fe38f4918162f937052fac98e083f"
)
_DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def build_release_predicate(
    *,
    version: str,
    source_commit: str,
    azure_profile: str,
    benchmark_record_digest: str,
    image_digests: Mapping[str, str],
) -> dict[str, Any]:
    if not _DIGEST.fullmatch(benchmark_record_digest):
        raise ValueError("benchmark record digest must be a sha256 digest")
    if not image_digests:
        raise ValueError("release predicate requires at least one image digest")
    for reference, digest in image_digests.items():
        if not _DIGEST.fullmatch(digest):
            raise ValueError(f"invalid image digest for {reference}")
    return {
        "schemaVersion": 1,
        "version": version,
        "sourceCommit": source_commit,
        "azureProfile": azure_profile,
        "benchmarkRecordDigest": benchmark_record_digest,
        "imageDigests": dict(sorted(image_digests.items())),
    }


def render_predicate(predicate: Mapping[str, Any]) -> str:
    return json.dumps(predicate, indent=2, sort_keys=True) + "\n"


def performance_root(repo_root: Path) -> Path:
    return repo_root / "docs" / "performance"


def attest_release_images(
    provider: object,
    request: object,
    *,
    images: Mapping[str, str],
    predicate_remote: str,
    sbom_dir_remote: str,
    cosign: RemoteCosignCredentials,
    docker_config: str,
) -> None:
    """SBOM, sign, attach, and verify every published digest, in that order."""
    if cosign.password_file is None:
        raise ValueError("cosign attestation requires a staged password file")
    _exec(provider, request, ("mkdir", "-p", sbom_dir_remote))
    # aliases pin to the same digest as their native manifests: work on the
    # unique pinned set so no digest is SBOM'd or signed twice
    pinned: dict[str, str] = {}
    for reference, digest in sorted(images.items()):
        image = f"{reference.rsplit(':', 1)[0]}@{digest}"
        pinned.setdefault(image, reference)
    sboms: dict[str, str] = {}
    for image, reference in sorted(pinned.items()):
        sbom_file = f"{sbom_dir_remote}/{_artifact_slug(reference)}.spdx.json"
        sboms[image] = sbom_file
        _exec(
            provider,
            request,
            (
                "docker",
                "run",
                "--rm",
                "--env",
                "DOCKER_CONFIG=/auth",
                "--volume",
                f"{docker_config}:/auth:ro",
                "--volume",
                f"{sbom_dir_remote}:/out",
                SYFT_IMAGE,
                image,
                "-o",
                f"spdx-json=/out/{Path(sbom_file).name}",
            ),
        )
    for image in sorted(pinned):
        _cosign(
            provider,
            request,
            cosign,
            docker_config,
            ("sign", "--yes", "--key", "/secrets/cosign-key", image),
        )
        _cosign(
            provider,
            request,
            cosign,
            docker_config,
            (
                "attest",
                "--yes",
                "--key",
                "/secrets/cosign-key",
                "--type",
                "custom",
                "--predicate",
                "/work/predicate.json",
                image,
            ),
            extra_mounts=((predicate_remote, "/work/predicate.json"),),
        )
        _cosign(
            provider,
            request,
            cosign,
            docker_config,
            (
                "attach",
                "sbom",
                "--sbom",
                "/work/sbom.spdx.json",
                "--type",
                "spdx",
                image,
            ),
            extra_mounts=((sboms[image], "/work/sbom.spdx.json"),),
        )
    for image in sorted(pinned):
        _cosign(
            provider,
            request,
            cosign,
            docker_config,
            ("verify", "--key", "/secrets/cosign-key", image),
        )
        _cosign(
            provider,
            request,
            cosign,
            docker_config,
            ("verify-attestation", "--key", "/secrets/cosign-key", "--type", "custom", image),
        )


def finalize_release(
    journal: ReleaseJournal,
    *,
    record: Mapping[str, Any],
    performance_root: Path,
) -> tuple[ArtifactEvidence, ...]:
    """Atomically publish the performance record and history, then journal it.

    A failure writing either documentation file leaves the journal without its
    final record, so `--resume` retries finalization without rebuilding.
    """
    version = str(record["version"])
    releases_dir = performance_root / "releases"
    releases_dir.mkdir(parents=True, exist_ok=True)
    release_file = releases_dir / f"{version}.json"
    _write_atomic(release_file, render_release_record(record))

    records = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(releases_dir.glob("*.json"))
    ]
    history_file = performance_root / "history.md"
    _write_atomic(history_file, render_history(records))

    evidence = (
        ArtifactEvidence("local", str(release_file), digest_path(release_file)),
        ArtifactEvidence("local", str(history_file), digest_path(history_file)),
    )
    journal.record("finalize", artifacts=evidence)
    return evidence


def _write_atomic(path: Path, content: str) -> None:
    staging = path.with_name(path.name + ".tmp")
    staging.write_text(content, encoding="utf-8")
    staging.replace(path)


def _artifact_slug(reference: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]+", "-", reference.split("/")[-1])


def _cosign(
    provider: object,
    request: object,
    cosign: RemoteCosignCredentials,
    docker_config: str,
    arguments: tuple[str, ...],
    *,
    extra_mounts: tuple[tuple[str, str], ...] = (),
) -> None:
    secrets_dir = str(Path(cosign.key_file).parent)
    mounts: list[str] = [
        "--volume",
        f"{secrets_dir}:/secrets:ro",
        "--volume",
        f"{docker_config}:/auth:ro",
    ]
    for source, target in extra_mounts:
        mounts.extend(("--volume", f"{source}:{target}:ro"))
    docker_argv = (
        "docker",
        "run",
        "--rm",
        # The pinned cosign image runs as a non-root user which cannot read
        # the 0600 azureuser-owned key/auth mounts; root inside the container
        # can, and the files stay private on the host.
        "--user",
        "0",
        "--env",
        "DOCKER_CONFIG=/auth",
        "--env",
        "COSIGN_PASSWORD",
        *mounts,
        COSIGN_IMAGE,
        *arguments,
    )
    # The password value exists only inside the remote shell's environment,
    # read from its staged file; argv carries the file path alone.
    wrapped = (
        "sh",
        "-c",
        'COSIGN_PASSWORD="$(cat "$1")" && export COSIGN_PASSWORD && shift && exec "$@"',
        "nanofaas-release-cosign",
        str(cosign.password_file),
        *docker_argv,
    )
    _exec(provider, request, wrapped)


def _exec(provider: object, request: object, argv: tuple[str, ...]) -> object:
    result = retry_on_connection_death(
        lambda: provider.exec_argv(  # type: ignore[attr-defined]
            request, argv, env=None, cwd=None, dry_run=False
        ),
        describe=f"attestation {argv[0]}",
    )
    if int(getattr(result, "return_code", 0)) != 0:
        raise RuntimeError(f"release attestation command failed: {argv[0]}")
    return result
