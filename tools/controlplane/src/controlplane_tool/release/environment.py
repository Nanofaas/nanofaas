"""Validation for the one comparable Azure release environment."""

from __future__ import annotations

import ipaddress
import re
from pathlib import Path

from controlplane_tool.config import EnvironmentConfig
from controlplane_tool.release.versioning import normalize_version, verify_version_consistency


_STACK_VM_SIZE = "Standard_D8s_v5"
_LOADGEN_VM_SIZE = "Standard_D2s_v5"
_ARM_VM_SIZE = "Standard_D8ps_v5"
_LOCATION = "westeurope"
_STACK_DISK = "128G"
_LOADGEN_DISK = "30G"
_ARM_DISK = "64G"
_STACK_NAME = "nanofaas-azure-release"
_LOADGEN_NAME = "nanofaas-azure-release-loadgen"
_ARM_NAME = "nanofaas-azure-release-arm"
_URN_COMPONENT = re.compile(r"[A-Za-z0-9._-]+")


def validate_release_environment(
    environment: EnvironmentConfig,
    repo_root: Path,
    requested_version: str,
) -> None:
    """Reject any environment that cannot produce comparable Azure evidence."""
    if environment.provider != "azure" or environment.azure is None:
        raise ValueError("release requires an Azure environment")
    if not {"stack", "loadgen", "arm-builder"}.issubset(environment.roles):
        raise ValueError("release environment requires stack, loadgen and arm-builder roles")

    azure = environment.azure
    _require_exact("Azure location", azure.location, _LOCATION)
    _require_exact("stack VM size", azure.vm_size, _STACK_VM_SIZE)
    _require_exact("loadgen VM size", azure.loadgen_vm_size, _LOADGEN_VM_SIZE)
    _require_exact("arm-builder VM size", azure.arm_vm_size, _ARM_VM_SIZE)
    _require_exact("stack disk", environment.roles["stack"].disk, _STACK_DISK)
    _require_exact("loadgen disk", environment.roles["loadgen"].disk, _LOADGEN_DISK)
    _require_exact("arm-builder disk", environment.roles["arm-builder"].disk, _ARM_DISK)
    _require_exact(
        "stack dedicated release VM name",
        environment.roles["stack"].name or "",
        _STACK_NAME,
    )
    _require_exact(
        "loadgen dedicated release VM name",
        environment.roles["loadgen"].name or "",
        _LOADGEN_NAME,
    )
    _require_exact(
        "arm-builder dedicated release VM name",
        environment.roles["arm-builder"].name or "",
        _ARM_NAME,
    )
    _validate_image_urn(azure.image_urn)
    _validate_image_urn(azure.arm_image_urn)
    _validate_operator_source(azure.operator_source_cidr)

    requested_plain, _ = normalize_version(requested_version)
    if verify_version_consistency(repo_root) != requested_plain:
        raise ValueError("requested version does not match the prepared project version")


def _require_exact(name: str, actual: str, expected: str) -> None:
    if actual.lower().startswith("standard_b"):
        raise ValueError(f"{name} must not use a burstable VM size")
    if actual != expected:
        raise ValueError(f"{name} must be {expected}")


def _validate_image_urn(urn: str | None) -> None:
    parts = urn.split(":") if urn is not None else ()
    if (
        len(parts) != 4
        or any(_URN_COMPONENT.fullmatch(part) is None for part in parts)
        or parts[-1].lower() == "latest"
    ):
        raise ValueError("Azure image URN must be an exact, non-latest four-part URN")


def _validate_operator_source(source: str | None) -> None:
    try:
        network = ipaddress.ip_network(source, strict=True) if source is not None else None
    except ValueError:
        network = None
    if network is None or network.prefixlen == 0:
        raise ValueError("Azure operator source CIDR must be a restricted network")
