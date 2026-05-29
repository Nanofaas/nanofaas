from __future__ import annotations

from pathlib import Path

from workflow_tasks.components.platform_commands import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)


def test_status_command() -> None:
    assert platform_status_command("nf") == ["platform", "status", "-n", "nf"]


def test_uninstall_command() -> None:
    assert platform_uninstall_command(release="cp", namespace="nf") == [
        "platform", "uninstall", "--release", "cp", "-n", "nf",
    ]


def test_install_command_splits_image_repo_and_tag() -> None:
    cmd = platform_install_command(
        repo_root=Path("/repo"),
        release="cp",
        namespace="nf",
        control_plane_image="reg:5000/nanofaas/control-plane:e2e",
    )
    assert cmd[:2] == ["platform", "install"]
    i = cmd.index("--control-plane-repository")
    assert cmd[i + 1] == "reg:5000/nanofaas/control-plane"
    j = cmd.index("--control-plane-tag")
    assert cmd[j + 1] == "e2e"
    assert any("/repo/helm/nanofaas" in str(part) for part in cmd)


def test_install_command_defaults_tag_to_latest_when_no_colon() -> None:
    cmd = platform_install_command(
        repo_root=Path("/repo"), release="cp", namespace="nf",
        control_plane_image="control-plane",
    )
    j = cmd.index("--control-plane-tag")
    assert cmd[j + 1] == "latest"
