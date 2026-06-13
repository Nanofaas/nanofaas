from __future__ import annotations

from controlplane_tool.tui import app as tui_app


def _values(choices) -> list[str]:
    return [c.value for c in choices]


def test_platform_menu_holds_only_validations() -> None:
    assert _values(tui_app._PLATFORM_VALIDATION_CHOICES) == [
        "validate-k3s",
        "validate-container-local",
        "validate-docker-pool",
        "validate-buildpack-pool",
    ]


def test_loadtest_vm_menu_lists_loadtests_legacy_last() -> None:
    assert _values(tui_app._LOADTEST_VM_CHOICES) == [
        "loadtest-one-vm",
        "loadtest-two-vm",
        "loadtest-azure",
        "loadtest-proxmox",
        "loadtest-helm-legacy",
    ]


def test_loadtest_menu_forks_local_and_vm() -> None:
    values = _values(tui_app._LOADTEST_ACTION_CHOICES)
    assert "local" in values and "vm" in values
