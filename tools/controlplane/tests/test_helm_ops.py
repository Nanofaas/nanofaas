from __future__ import annotations

from pathlib import Path

from controlplane_tool.helm_ops import HelmOps


def test_helm_ops_build_upgrade_install_command() -> None:
    command = HelmOps(Path("/repo")).upgrade_install(
        release="control-plane",
        chart=Path("helm/nanofaas"),
        namespace="nanofaas-e2e",
        values={"controlPlane.image.tag": "e2e"},
        dry_run=True,
    )

    assert command.command[:3] == ["helm", "upgrade", "--install"]
    assert "--set" in command.command
    assert "controlPlane.image.tag=e2e" in command.command
