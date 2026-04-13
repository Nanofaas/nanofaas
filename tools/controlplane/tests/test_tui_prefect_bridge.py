from controlplane_tool.tui_prefect_bridge import TuiPrefectBridge
from controlplane_tool.workflow_events import build_log_event, build_task_event


def test_tui_bridge_maps_task_started_event_to_running_step() -> None:
    bridge = TuiPrefectBridge()

    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k8s_vm",
            task_id="vm.ensure_running",
            title="Ensure VM is running",
        )
    )

    snapshot = bridge.snapshot()
    assert snapshot.phases[0].task_id == "vm.ensure_running"
    assert snapshot.phases[0].label == "Ensure VM is running"
    assert snapshot.phases[0].status == "running"


def test_tui_bridge_preserves_log_buffer_across_toggle() -> None:
    bridge = TuiPrefectBridge()

    bridge.handle_event(
        build_log_event(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            line="docker push ok",
        )
    )
    bridge.toggle_logs()
    bridge.toggle_logs()

    snapshot = bridge.snapshot()
    assert snapshot.show_logs is True
    assert "docker push ok" in snapshot.logs[-1]


def test_tui_bridge_routes_updated_cancelled_and_log_events_through_same_task_row() -> None:
    bridge = TuiPrefectBridge()

    bridge.handle_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
            detail="50%",
        )
    )
    bridge.handle_event(
        build_log_event(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            line="docker build layer cached",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.cancelled",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
            detail="cancelled by user",
        )
    )

    snapshot = bridge.snapshot()
    assert len(snapshot.phases) == 1
    assert snapshot.phases[0].task_id == "images.build_core"
    assert snapshot.phases[0].detail == "cancelled by user"
    assert snapshot.phases[0].status == "cancelled"
    assert any("docker build layer cached" in line for line in snapshot.logs)


def test_tui_bridge_reuses_planned_placeholder_when_log_arrives_before_task_running() -> None:
    bridge = TuiPrefectBridge(planned_steps=["Build core images"])

    bridge.handle_event(
        build_log_event(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            line="docker build started",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
        )
    )

    snapshot = bridge.snapshot()
    assert len(snapshot.phases) == 1
    assert snapshot.phases[0].task_id == "images.build_core"
    assert snapshot.phases[0].status == "running"


def test_tui_bridge_task_updated_reactivates_failed_task() -> None:
    bridge = TuiPrefectBridge()

    bridge.handle_event(
        build_task_event(
            kind="task.failed",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
            detail="first attempt failed",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
            detail="Retrying",
        )
    )

    snapshot = bridge.snapshot()
    assert snapshot.phases[0].status == "running"
    assert snapshot.phases[0].detail == "Retrying"


def test_tui_bridge_does_not_mark_lower_planned_step_success_when_higher_step_starts() -> None:
    bridge = TuiPrefectBridge(
        planned_steps=[
            "Ensure VM is running",
            "Provision base VM dependencies",
        ]
    )

    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k8s_vm",
            task_id="vm.ensure_running",
            title="Ensure VM is running",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k8s_vm",
            task_id="vm.provision_base",
            title="Provision base VM dependencies",
        )
    )

    snapshot = bridge.snapshot()
    assert [phase.status for phase in snapshot.phases] == ["running", "running"]


def test_nested_verify_events_do_not_create_new_top_level_rows() -> None:
    bridge = TuiPrefectBridge(
        planned_steps=[
            "Ensure VM is running",
            "Provision base VM dependencies",
            "Sync project to VM",
            "Run k3s-junit-curl verification",
            "Delete E2E namespace",
            "Teardown VM",
        ]
    )

    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k3s_junit_curl",
            task_id="tests.run_k3s_curl_checks",
            title="Run k3s-junit-curl verification",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k3s_junit_curl",
            task_id="verify.control_plane_health",
            title="Verify",
            detail="Verifying control-plane health",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k3s_junit_curl",
            task_id="verify.prometheus_metrics",
            title="Verify",
            detail="Verifying Prometheus metrics",
        )
    )

    snapshot = bridge.snapshot()

    assert snapshot.phases[3].label == "Run k3s-junit-curl verification"
    assert snapshot.phases[3].task_id == "tests.run_k3s_curl_checks"
    assert snapshot.phases[3].detail == "Verifying Prometheus metrics"
    assert all(phase.label != "Verify" for phase in snapshot.phases)
