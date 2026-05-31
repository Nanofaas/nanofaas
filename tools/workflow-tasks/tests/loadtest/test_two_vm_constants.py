from __future__ import annotations

from workflow_tasks.loadtest.two_vm import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
    TWO_VM_PROMETHEUS_NODE_PORT,
    TWO_VM_REMOTE_DIR_NAME,
)


def test_node_ports_are_stable() -> None:
    assert TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT == 30080
    assert TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT == 30081
    assert TWO_VM_PROMETHEUS_NODE_PORT == 30090


def test_remote_dir_name() -> None:
    assert TWO_VM_REMOTE_DIR_NAME == "two-vm-loadtest"


def test_loadtest_scenarios_is_nonempty_frozenset() -> None:
    assert isinstance(LOADTEST_SCENARIOS, frozenset)
    assert len(LOADTEST_SCENARIOS) >= 1
