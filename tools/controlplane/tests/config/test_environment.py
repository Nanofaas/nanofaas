from __future__ import annotations

import pytest
from pydantic import ValidationError

from controlplane_tool.config.environment import EnvironmentConfig


def test_local_environment_defaults_stack_to_host() -> None:
    config = EnvironmentConfig(provider="local")

    assert config.target("stack") == config.target("host")


def test_external_environment_requires_stack_host() -> None:
    with pytest.raises(ValidationError, match="stack host is required"):
        EnvironmentConfig(provider="external")


def test_external_environment_preserves_distinct_stack_and_loadgen() -> None:
    config = EnvironmentConfig.model_validate(
        {
            "provider": "external",
            "roles": {
                "stack": {"host": "stack.example", "user": "stack-user"},
                "loadgen": {"host": "load.example", "user": "load-user"},
            },
        }
    )

    assert config.target("stack").host == "stack.example"
    assert config.target("loadgen").host == "load.example"


def test_multipass_environment_uses_role_names() -> None:
    config = EnvironmentConfig.model_validate(
        {
            "provider": "multipass",
            "roles": {
                "stack": {"name": "nanofaas-stack"},
                "loadgen": {"name": "nanofaas-loadgen"},
            },
        }
    )

    assert config.target("stack").name == "nanofaas-stack"
    assert config.target("loadgen").name == "nanofaas-loadgen"


def test_multipass_environment_requires_stack_name() -> None:
    with pytest.raises(ValidationError, match="stack name is required"):
        EnvironmentConfig(provider="multipass")


def test_azure_environment_requires_provider_configuration() -> None:
    with pytest.raises(ValidationError, match="azure configuration is required"):
        EnvironmentConfig(provider="azure")


def test_proxmox_environment_requires_provider_configuration() -> None:
    with pytest.raises(ValidationError, match="proxmox configuration is required"):
        EnvironmentConfig(provider="proxmox")


def test_rejects_legacy_prefect_configuration() -> None:
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        EnvironmentConfig.model_validate(
            {"provider": "local", "prefect": {"enabled": True}}
        )
