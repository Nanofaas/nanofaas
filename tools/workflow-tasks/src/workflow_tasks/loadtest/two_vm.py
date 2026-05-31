"""Pure two-VM loadtest constants (node ports, scenario set, remote dir name)."""
from __future__ import annotations

LOADTEST_SCENARIOS: frozenset[str] = frozenset(
    {
        "two-vm-loadtest",
        "azure-vm-loadtest",
        "proxmox-vm-loadtest",
    }
)

TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT = 30080
TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT = 30081
TWO_VM_PROMETHEUS_NODE_PORT = 30090
TWO_VM_REMOTE_DIR_NAME = "two-vm-loadtest"

__all__ = [
    "LOADTEST_SCENARIOS",
    "TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT",
    "TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT",
    "TWO_VM_PROMETHEUS_NODE_PORT",
    "TWO_VM_REMOTE_DIR_NAME",
]
