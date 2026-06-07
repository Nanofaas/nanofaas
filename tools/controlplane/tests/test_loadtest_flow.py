from __future__ import annotations

from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext


def test_run_context_starts_empty_and_is_mutable() -> None:
    ctx = RunContext()
    assert ctx.stack_info is None
    assert ctx.loadgen_info is None
    assert ctx.control_plane_url is None
    assert ctx.prometheus_url is None
    assert ctx.run_dir is None
    assert ctx.remote_paths is None
    assert ctx.stack_host is None
    ctx.stack_host = "10.0.0.5"
    assert ctx.stack_host == "10.0.0.5"


def test_flow_phase_members() -> None:
    assert {p.name for p in FlowPhase} >= {"AFTER_STACK_READY", "BEFORE_LOADGEN"}


def test_run_loadtest_flow_orders_phases_and_populates_context(monkeypatch) -> None:
    """The driver runs ensure-stack -> prelude -> ensure-loadgen -> prepare ->
    register -> loadgen body -> cleanup, threading RunContext."""
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    events: list[str] = []

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeAdapter:
        title_suffix = " (Fake)"
        connectivity = object()
        def stack_lifecycle(self): return "stack-lc"
        def loadgen_lifecycle(self): return "loadgen-lc"
        def loadgen_install_endpoint(self, ctx):
            from controlplane_tool.scenario.loadtest_adapter import InstallEndpoint
            return InstallEndpoint(host="1.1.1.1", user="ubuntu", private_key=None, port=None)
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): events.append("prepare")
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): events.append(f"extra:{phase.name}"); return []

    monkeypatch.setattr(mod, "_ensure_vm", lambda task_id, title, lifecycle, config: events.append(task_id) or FakeInfo())
    monkeypatch.setattr(mod, "_build_prelude_tasks", lambda runner, request, setup, recipe, connectivity: [])
    monkeypatch.setattr(mod, "_run_workflow", lambda tasks, cleanup_tasks=None: events.append("workflow"))
    monkeypatch.setattr(mod, "_register_functions", lambda runner, request, setup, ctx: events.append("register"))
    monkeypatch.setattr(mod, "_build_loadgen_body", lambda runner, request, adapter, ctx: events.append("body") or [])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    setup = type("S", (), {"vm_config": object(), "context": object()})()
    request = type("R", (), {"cleanup_vm": False,
                             "loadgen_vm": type("L", (), {"name": "lg", "cpus": 1, "memory": "1G", "disk": "5G", "user": "ubuntu"})()})()

    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(), adapter=FakeAdapter())

    # ensure-stack happens before any workflow or loadgen step
    assert events.index("vm.stack.ensure_running") < events.index("workflow")
    # prelude workflow fires before loadgen is ensured
    assert events.index("workflow") < events.index("vm.loadgen.ensure_running")
    # prepare happens after loadgen is ensured (mirroring two-vm's run())
    assert events.index("vm.loadgen.ensure_running") < events.index("prepare")
    # register happens after prepare (mirroring two-vm's run())
    assert events.index("prepare") < events.index("register")
    # loadgen body is built after register
    assert events.index("register") < events.index("body")
