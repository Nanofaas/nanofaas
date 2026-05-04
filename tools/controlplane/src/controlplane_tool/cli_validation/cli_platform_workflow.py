from __future__ import annotations

from pathlib import Path


def _image_parts(image: str) -> tuple[str, str]:
    repository, separator, tag = image.rpartition(":")
    if not separator:
        return image, "latest"
    return repository, tag


def platform_install_command(
    *,
    repo_root: Path,
    release: str,
    namespace: str,
    control_plane_image: str,
) -> list[str]:
    repository, tag = _image_parts(control_plane_image)
    return [
        "platform",
        "install",
        "--release",
        release,
        "-n",
        namespace,
        "--chart",
        str(repo_root / "helm" / "nanofaas"),
        "--control-plane-repository",
        repository,
        "--control-plane-tag",
        tag,
        "--control-plane-pull-policy",
        "Always",
        "--demos-enabled=false",
    ]


def platform_status_command(namespace: str) -> list[str]:
    return ["platform", "status", "-n", namespace]


def platform_uninstall_command(*, release: str, namespace: str) -> list[str]:
    return ["platform", "uninstall", "--release", release, "-n", namespace]

