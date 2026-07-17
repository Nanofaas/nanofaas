from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from controlplane_tool.config import EnvironmentConfig
from controlplane_tool.release.environment import validate_release_environment


SOURCE_REPO = Path(__file__).resolve().parents[4]


def _release_environment(**changes: object) -> EnvironmentConfig:
    data: dict[str, object] = {
        "provider": "azure",
        "roles": {
            "stack": {"name": "nanofaas-azure-release", "disk": "128G"},
            "loadgen": {
                "name": "nanofaas-azure-release-loadgen",
                "disk": "30G",
            },
        },
        "azure": {
            "resource_group": "nanofaas-rg",
            "location": "westeurope",
            "vm_size": "Standard_D4s_v5",
            "loadgen_vm_size": "Standard_D2s_v5",
            "image_urn": "Canonical:ubuntu-24_04-lts:server:24.04.202505280",
        },
    }
    data.update(changes)
    return EnvironmentConfig.model_validate(data)


def test_release_environment_example_is_pinned_and_comparable() -> None:
    path = SOURCE_REPO / "tools/controlplane/environments/azure-release.yaml.example"
    environment = EnvironmentConfig.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))

    validate_release_environment(environment, SOURCE_REPO, "0.17.0")
    assert environment.roles["stack"].disk == "128G"
    assert environment.roles["loadgen"].disk == "30G"


@pytest.mark.parametrize("provider", ("local", "multipass", "proxmox"))
def test_release_environment_rejects_non_azure_provider(provider: str) -> None:
    changes: dict[str, object] = {"provider": provider, "azure": None}
    if provider == "proxmox":
        changes["proxmox"] = {"host": "pve.example.test", "node": "pve"}
    environment = _release_environment(**changes)

    with pytest.raises(ValueError, match="Azure"):
        validate_release_environment(environment, SOURCE_REPO, "0.17.0")


@pytest.mark.parametrize("roles", ({"stack": {"disk": "128G"}}, {"loadgen": {"disk": "30G"}}))
def test_release_environment_requires_stack_and_loadgen_roles(roles: dict[str, object]) -> None:
    environment = _release_environment(roles=roles)

    with pytest.raises(ValueError, match="stack and loadgen"):
        validate_release_environment(environment, SOURCE_REPO, "0.17.0")


def test_release_environment_rejects_latest_urn() -> None:
    environment = _release_environment(
        azure={
            "resource_group": "nanofaas-rg",
            "location": "westeurope",
            "vm_size": "Standard_D4s_v5",
            "loadgen_vm_size": "Standard_D2s_v5",
            "image_urn": "Canonical:ubuntu-24_04-lts:server:latest",
        }
    )

    with pytest.raises(ValueError, match="image URN"):
        validate_release_environment(environment, SOURCE_REPO, "0.17.0")


@pytest.mark.parametrize("field", ("vm_size", "loadgen_vm_size"))
def test_release_environment_rejects_burstable_vm_size(field: str) -> None:
    azure = {
        "resource_group": "nanofaas-rg",
        "location": "westeurope",
        "vm_size": "Standard_D4s_v5",
        "loadgen_vm_size": "Standard_D2s_v5",
        "image_urn": "Canonical:ubuntu-24_04-lts:server:24.04.202505280",
    }
    azure[field] = "Standard_B2s"
    environment = _release_environment(azure=azure)

    with pytest.raises(ValueError, match="burstable"):
        validate_release_environment(environment, SOURCE_REPO, "0.17.0")


def test_release_environment_rejects_unprepared_project_version() -> None:
    with pytest.raises(ValueError, match="prepared project version"):
        validate_release_environment(_release_environment(), SOURCE_REPO, "0.18.0")
