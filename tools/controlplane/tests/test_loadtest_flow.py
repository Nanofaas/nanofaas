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
        def emits_step_events(self): return False
        def register_functions(self, ctx): events.append("register")

    monkeypatch.setattr(mod, "_ensure_vm", lambda task_id, title, lifecycle, config: events.append(task_id) or FakeInfo())
    monkeypatch.setattr(mod, "_build_prelude_tasks", lambda runner, request, setup, recipe, connectivity: [])
    monkeypatch.setattr(mod, "_run_workflow", lambda tasks, cleanup_tasks=None: events.append("workflow"))
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


_EXPECTED_TWO_VM_TASK_IDS = [
    "vm.stack.ensure_running",
    "vm.provision_base", "repo.sync_to_vm", "registry.ensure_container",
    "images.build_core", "images.build_selected_functions", "k3s.install",
    "k3s.configure_registry", "namespace.install", "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "vm.loadgen.ensure_running",
    "loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
    "metrics.prometheus_snapshot", "loadtest.write_report",
    "vm.loadgen.destroy", "vm.stack.destroy",
]

_EXPECTED_LOADGEN_BODY_IDS = [
    "loadgen.install_k6",
    "loadgen.run_k6",
    "loadgen.fetch_results",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
]


def test_static_task_ids_match_two_vm(monkeypatch) -> None:
    from controlplane_tool.scenario import loadtest_flow as mod

    prelude_ids = _EXPECTED_TWO_VM_TASK_IDS[1:11]
    monkeypatch.setattr(mod, "_prelude_static_ids",
                        lambda runner, request, setup, recipe, connectivity: prelude_ids)

    class FakeAdapter:
        title_suffix = ""
        connectivity = object()
        def extra_step_ids(self, phase): return []

    ids = mod.loadtest_flow_task_ids(runner=object(), request=object(), setup=object(),
                                     recipe=object(), adapter=FakeAdapter())
    assert ids == _EXPECTED_TWO_VM_TASK_IDS


def test_static_phase_titles_match_two_vm(monkeypatch) -> None:
    from controlplane_tool.scenario import loadtest_flow as mod

    class FakeTask:
        def __init__(self, title): self.title = title

    prelude_titles = ["Provision base", "Sync project to VM", "Ensure registry",
                      "Build core", "Build functions", "Install k3s",
                      "Configure registry", "Install namespace", "Deploy control plane",
                      "Deploy function runtime"]
    monkeypatch.setattr(mod, "_prelude_static_tasks",
                        lambda runner, request, setup, recipe, connectivity: [FakeTask(t) for t in prelude_titles])

    class FakeAdapter:
        title_suffix = ""
        connectivity = object()
        def extra_step_ids(self, phase): return []

    titles = mod.loadtest_flow_phase_titles(runner=object(), request=object(), setup=object(),
                                            recipe=object(), adapter=FakeAdapter())
    assert titles == (
        ["Ensure stack VM running"] + prelude_titles + [
            "Ensure loadgen VM running", "Install k6 on loadgen VM", "Run k6 loadtest",
            "Fetch k6 results from loadgen VM", "Capture Prometheus snapshots",
            "Write loadtest report", "Destroy loadgen VM", "Destroy stack VM",
        ]
    )


# ── Task 4: adapter opt-in capabilities (multipass path stays unchanged) ─────


def _emitting_setup_request():
    from pathlib import Path
    setup = type("S", (), {"vm_config": object(), "context": object()})()
    request = type("R", (), {"cleanup_vm": False,
                             "loadgen_vm": type("L", (), {"name": "lg", "cpus": 1, "memory": "1G", "disk": "5G", "user": "ubuntu"})()})()
    return setup, request, Path


def test_emitting_adapter_emits_running_then_success_per_task(monkeypatch) -> None:
    """When emits_step_events() is True, the driver emits running->success per
    executed task with sequential step_index and a constant total_steps."""
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    setup, request, _ = _emitting_setup_request()

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeTask:
        def __init__(self, task_id, title):
            self.task_id = task_id
            self.title = title

        def run(self):
            return None

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
        def prepare_loadgen(self, ctx): pass
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): return []
        def extra_step_ids(self, phase): return []
        def extra_step_titles(self, phase): return []
        def emits_step_events(self): return True
        def cleanup_on_failure(self, error): return []
        def prelude_special_handler(self, ctx): return None
        def prelude_context_selector(self, ctx): return None
        def register_functions(self, ctx): pass

    # Pin the static plan length so total_steps is deterministic.
    monkeypatch.setattr(mod, "loadtest_flow_task_ids",
                        lambda **kw: ["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"])
    monkeypatch.setattr(mod, "_ensure_vm_task",
                        lambda task_id, title, lifecycle, config: FakeTask(task_id, title))
    monkeypatch.setattr(mod, "_build_prelude_tasks",
                        lambda runner, request, setup, recipe, connectivity, special_handler=None, context_selector=None:
                        [FakeTask("prelude.a", "Prelude A")])
    monkeypatch.setattr(mod, "_build_loadgen_body",
                        lambda runner, request, adapter, ctx:
                        [FakeTask(tid, tid) for tid in ("loadgen.install_k6", "loadgen.run_k6",
                                                        "loadgen.fetch_results",
                                                        "metrics.prometheus_snapshot",
                                                        "loadtest.write_report")])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    events = []
    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(),
                          adapter=FakeAdapter(), event_listener=events.append)

    # Constant total_steps.
    assert {e.total_steps for e in events} == {9}
    # Every executed task emits running then success.
    seq = [(e.step_index, e.step.step_id, e.status) for e in events]
    statuses = [s for _, _, s in seq]
    assert all(s in ("running", "success") for s in statuses)
    # Indices are sequential, paired running->success.
    indices = [idx for idx, _, _ in seq]
    pairs = list(zip(indices[::2], indices[1::2]))
    assert all(a == b for a, b in pairs)  # running/success share index
    distinct_indices = [a for a, _ in pairs]
    assert distinct_indices == list(range(1, len(distinct_indices) + 1))
    # ensure-stack comes first (matches the static plan order), then prelude.
    running = [(idx, sid) for idx, sid, st in seq if st == "running"]
    assert running[0] == (1, "vm.stack.ensure_running")
    assert running[1] == (2, "prelude.a")


def test_emitting_path_ensures_stack_before_resolving_connectivity(monkeypatch) -> None:
    """Regression: the emitting path (proxmox/azure) must ensure the stack VM BEFORE
    building the prelude.

    Building the prelude resolves the host via ``adapter.connectivity_for(resolve_host=True)``,
    which for proxmox calls ``get_vm()`` and raises ``VmNotFoundError`` when the VM does
    not exist yet. Previously the prelude was built first, so a fresh proxmox run failed
    at flow-construction time with ``VM not found: '<name>'`` before any phase started.
    """
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    setup, request, _ = _emitting_setup_request()
    ensured = {"stack": False}
    order: list[str] = []

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeTask:
        def __init__(self, task_id, title):
            self.task_id = task_id
            self.title = title

        def run(self):
            order.append(self.task_id)
            if self.task_id == "vm.stack.ensure_running":
                ensured["stack"] = True
            return FakeInfo()

    class FakeAdapter:
        title_suffix = " (Fake)"
        connectivity = object()

        def connectivity_for(self, ctx, *, resolve_host):
            # Mirror proxmox: resolving the host requires the stack VM to exist.
            if resolve_host and not ensured["stack"]:
                raise RuntimeError("VM not found: 'nanofaas-proxmox'")
            return object()

        def stack_lifecycle(self): return "stack-lc"
        def loadgen_lifecycle(self): return "loadgen-lc"
        def loadgen_install_endpoint(self, ctx):
            from controlplane_tool.scenario.loadtest_adapter import InstallEndpoint
            return InstallEndpoint(host="1.1.1.1", user="ubuntu", private_key=None, port=None)
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): pass
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): return []
        def extra_step_ids(self, phase): return []
        def extra_step_titles(self, phase): return []
        def emits_step_events(self): return True
        def cleanup_on_failure(self, error): return []
        def prelude_special_handler(self, ctx): return None
        def prelude_context_selector(self, ctx): return None
        def register_functions(self, ctx): pass

    monkeypatch.setattr(mod, "loadtest_flow_task_ids",
                        lambda **kw: ["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"])
    monkeypatch.setattr(mod, "_ensure_vm_task",
                        lambda task_id, title, lifecycle, config: FakeTask(task_id, title))
    monkeypatch.setattr(mod, "_build_prelude_tasks",
                        lambda runner, request, setup, recipe, connectivity, special_handler=None, context_selector=None:
                        [FakeTask("prelude.a", "Prelude A")])
    monkeypatch.setattr(mod, "_build_loadgen_body",
                        lambda runner, request, adapter, ctx:
                        [FakeTask(tid, tid) for tid in ("loadgen.install_k6", "loadgen.run_k6",
                                                        "loadgen.fetch_results",
                                                        "metrics.prometheus_snapshot",
                                                        "loadtest.write_report")])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    # Must NOT raise: the stack VM is ensured before connectivity is resolved.
    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(),
                          adapter=FakeAdapter(), event_listener=lambda e: None)

    assert order.index("vm.stack.ensure_running") < order.index("prelude.a")


def test_emitting_adapter_wraps_prelude_failure_with_cleanup(monkeypatch) -> None:
    """When a prelude-region task raises and cleanup_on_failure returns errors,
    the driver wraps the message with the scenario format and includes cleanup."""
    import pytest
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    setup, request, _ = _emitting_setup_request()
    request.scenario = "fake-scenario"

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class BoomTask:
        task_id = "prelude.boom"
        title = "Boom step"

        def run(self):
            raise RuntimeError("kaboom")

    class FakeAdapter:
        title_suffix = ""
        connectivity = object()

        def stack_lifecycle(self): return "stack-lc"
        def loadgen_lifecycle(self): return "loadgen-lc"
        def loadgen_install_endpoint(self, ctx): return None
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): pass
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): return []
        def extra_step_ids(self, phase): return []
        def extra_step_titles(self, phase): return []
        def emits_step_events(self): return True
        def cleanup_on_failure(self, error): return ["teardown failed: nope"]
        def prelude_special_handler(self, ctx): return None
        def prelude_context_selector(self, ctx): return None
        def register_functions(self, ctx): pass

    monkeypatch.setattr(mod, "loadtest_flow_task_ids", lambda **kw: ["t1", "t2"])
    monkeypatch.setattr(mod, "_ensure_vm_task",
                        lambda task_id, title, lifecycle, config:
                        type("T", (), {"task_id": task_id, "title": title, "run": lambda self: FakeInfo()})())
    monkeypatch.setattr(mod, "_build_prelude_tasks",
                        lambda runner, request, setup, recipe, connectivity, special_handler=None, context_selector=None:
                        [BoomTask()])

    with pytest.raises(RuntimeError) as exc_info:
        mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(),
                              adapter=FakeAdapter(), event_listener=lambda e: None)

    msg = str(exc_info.value)
    assert "Scenario 'fake-scenario' failed at step 'Boom step'" in msg
    assert "kaboom" in msg
    assert "teardown failed: nope" in msg


def test_static_plan_injects_extra_step_ids_and_titles_aligned(monkeypatch) -> None:
    """extra_step_ids and extra_step_titles are injected at the same BEFORE_LOADGEN
    position so the dry-run task_ids and phase_titles stay length-aligned."""
    from controlplane_tool.scenario import loadtest_flow as mod

    class FakeTask:
        def __init__(self, task_id, title):
            self.task_id = task_id
            self.title = title

    prelude = [FakeTask("vm.provision_base", "Provision base"),
               FakeTask("repo.sync_to_vm", "Sync project to VM")]
    monkeypatch.setattr(mod, "_prelude_static_tasks",
                        lambda runner, request, setup, recipe, connectivity: prelude)
    monkeypatch.setattr(mod, "_prelude_static_ids",
                        lambda runner, request, setup, recipe, connectivity: [t.task_id for t in prelude])

    class FakeAdapter:
        title_suffix = ""
        connectivity = object()

        def extra_step_ids(self, phase):
            if phase is FlowPhase.BEFORE_LOADGEN:
                return ["vm.stack.publish_ports"]
            return []

        def extra_step_titles(self, phase):
            if phase is FlowPhase.BEFORE_LOADGEN:
                return ["Publish Proxmox NAT ports"]
            return []

    ids = mod.loadtest_flow_task_ids(runner=object(), request=object(), setup=object(),
                                     recipe=object(), adapter=FakeAdapter())
    titles = mod.loadtest_flow_phase_titles(runner=object(), request=object(), setup=object(),
                                            recipe=object(), adapter=FakeAdapter())
    assert len(ids) == len(titles)
    assert "vm.stack.publish_ports" in ids
    assert "Publish Proxmox NAT ports" in titles
    assert ids.index("vm.stack.publish_ports") == titles.index("Publish Proxmox NAT ports")
    # Positioned right after ensure-loadgen, before the body.
    assert ids.index("vm.loadgen.ensure_running") < ids.index("vm.stack.publish_ports")
    assert ids.index("vm.stack.publish_ports") < ids.index("loadgen.install_k6")


def test_one_vm_static_task_ids_skip_loadgen_lifecycle_and_append_post_loadgen(
    monkeypatch,
) -> None:
    from controlplane_tool.scenario import loadtest_flow as mod

    prelude_ids = _EXPECTED_TWO_VM_TASK_IDS[1:11]
    monkeypatch.setattr(mod, "_prelude_static_ids",
                        lambda runner, request, setup, recipe, connectivity: prelude_ids)

    class FakeOneVmAdapter:
        title_suffix = " (One VM)"
        connectivity = object()

        def uses_dedicated_loadgen_vm(self):
            return False

        def extra_step_ids(self, phase):
            return []

        def post_loadgen_task_ids(self):
            return [
                "autoscaling.register_function",
                "autoscaling.run_k6",
                "autoscaling.verify_replicas",
            ]

    ids = mod.loadtest_flow_task_ids(runner=object(), request=object(), setup=object(),
                                     recipe=object(), adapter=FakeOneVmAdapter())

    assert "vm.loadgen.ensure_running" not in ids
    assert "vm.loadgen.destroy" not in ids
    assert ids == (
        ["vm.stack.ensure_running"]
        + prelude_ids
        + _EXPECTED_LOADGEN_BODY_IDS
        + [
            "autoscaling.register_function",
            "autoscaling.run_k6",
            "autoscaling.verify_replicas",
            "vm.stack.destroy",
        ]
    )


def test_one_vm_static_phase_titles_skip_loadgen_lifecycle_and_append_post_loadgen(
    monkeypatch,
) -> None:
    from controlplane_tool.scenario import loadtest_flow as mod

    class FakeTask:
        def __init__(self, title):
            self.title = title

    prelude_titles = [
        "Provision base", "Sync project to VM", "Ensure registry",
        "Build core", "Build functions", "Install k3s",
        "Configure registry", "Install namespace", "Deploy control plane",
        "Deploy function runtime",
    ]
    monkeypatch.setattr(mod, "_prelude_static_tasks",
                        lambda runner, request, setup, recipe, connectivity:
                        [FakeTask(t) for t in prelude_titles])

    class FakeOneVmAdapter:
        title_suffix = " (One VM)"
        connectivity = object()

        def uses_dedicated_loadgen_vm(self):
            return False

        def extra_step_ids(self, phase):
            return []

        def post_loadgen_task_titles(self):
            return [
                "Register autoscaling function",
                "Run autoscaling k6 tail",
                "Verify autoscaling replicas",
            ]

    titles = mod.loadtest_flow_phase_titles(runner=object(), request=object(), setup=object(),
                                            recipe=object(), adapter=FakeOneVmAdapter())

    assert "Ensure loadgen VM running (One VM)" not in titles
    assert "Destroy loadgen VM (One VM)" not in titles
    assert titles == (
        ["Ensure stack VM running (One VM)"]
        + prelude_titles
        + [
            "Install k6 on loadgen VM (One VM)",
            "Run k6 loadtest (One VM)",
            "Fetch k6 results from loadgen VM (One VM)",
            "Capture Prometheus snapshots (One VM)",
            "Write loadtest report (One VM)",
            "Register autoscaling function",
            "Run autoscaling k6 tail",
            "Verify autoscaling replicas",
            "Destroy stack VM (One VM)",
        ]
    )


def test_native_one_vm_flow_reuses_stack_info_and_runs_post_loadgen_before_cleanup(
    monkeypatch,
) -> None:
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    events: list[str] = []

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeTask:
        def __init__(self, task_id, title):
            self.task_id = task_id
            self.title = title

        def run(self):
            events.append(self.task_id)
            return None

    class FakeLifecycle:
        def destroy(self, info):
            events.append("vm.stack.destroy")

    class FakeOneVmAdapter:
        title_suffix = " (One VM)"
        connectivity = object()

        def uses_dedicated_loadgen_vm(self): return False
        def loadgen_info(self, ctx): return ctx.stack_info
        def stack_lifecycle(self): return FakeLifecycle()
        def loadgen_lifecycle(self): raise AssertionError("no loadgen lifecycle")
        def loadgen_install_endpoint(self, ctx): return None
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): events.append(f"prepare:{ctx.loadgen_info.home}")
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): return []
        def extra_step_ids(self, phase): return []
        def emits_step_events(self): return False
        def register_functions(self, ctx): events.append("register")
        def post_loadgen_tasks(self, ctx): return [FakeTask("autoscaling.run_k6", "Run autoscaling")]

    monkeypatch.setattr(mod, "_ensure_vm",
                        lambda task_id, title, lifecycle, config:
                        events.append(task_id) or FakeInfo())
    monkeypatch.setattr(mod, "_build_prelude_tasks",
                        lambda runner, request, setup, recipe, connectivity: [])
    monkeypatch.setattr(mod, "_run_workflow",
                        lambda tasks, cleanup_tasks=None:
                        [task.run() for task in [*tasks, *(cleanup_tasks or [])]])
    monkeypatch.setattr(mod, "_build_loadgen_body",
                        lambda runner, request, adapter, ctx:
                        events.append("body") or [FakeTask("loadgen.run_k6", "Run k6")])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    setup = type("S", (), {"vm_config": object(), "context": object()})()
    request = type("R", (), {"cleanup_vm": True, "loadgen_vm": None})()

    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(),
                          adapter=FakeOneVmAdapter())

    assert "vm.loadgen.ensure_running" not in events
    assert "vm.loadgen.destroy" not in events
    assert events.index("loadgen.run_k6") < events.index("autoscaling.run_k6")
    assert events.index("autoscaling.run_k6") < events.index("vm.stack.destroy")
    assert "prepare:/home/ubuntu" in events


def test_emitting_one_vm_flow_skips_loadgen_lifecycle_and_emits_post_loadgen(
    monkeypatch,
) -> None:
    from pathlib import Path
    from controlplane_tool.scenario import loadtest_flow as mod

    setup, request, _ = _emitting_setup_request()
    request.cleanup_vm = True
    events: list[str] = []

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeTask:
        def __init__(self, task_id, title):
            self.task_id = task_id
            self.title = title

        def run(self):
            events.append(self.task_id)
            return FakeInfo() if self.task_id == "vm.stack.ensure_running" else None

    class FakeLifecycle:
        def destroy(self, info):
            events.append("vm.stack.destroy")

    class FakeOneVmAdapter:
        title_suffix = " (One VM)"
        connectivity = object()

        def uses_dedicated_loadgen_vm(self): return False
        def loadgen_info(self, ctx): return ctx.stack_info
        def stack_lifecycle(self): return FakeLifecycle()
        def loadgen_lifecycle(self): raise AssertionError("no loadgen lifecycle")
        def loadgen_install_endpoint(self, ctx): return None
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): events.append(f"prepare:{ctx.loadgen_info.home}")
        def create_run_dir(self): return Path("/tmp/run")
        def extra_steps(self, phase, ctx): return []
        def extra_step_ids(self, phase): return []
        def extra_step_titles(self, phase): return []
        def emits_step_events(self): return True
        def cleanup_on_failure(self, error): return []
        def prelude_special_handler(self, ctx): return None
        def prelude_context_selector(self, ctx): return None
        def register_functions(self, ctx): events.append("register")
        def post_loadgen_tasks(self, ctx): return [FakeTask("autoscaling.run_k6", "Run autoscaling")]

    monkeypatch.setattr(mod, "loadtest_flow_task_ids",
                        lambda **kw: ["vm.stack.ensure_running", "loadgen.run_k6",
                                      "autoscaling.run_k6", "vm.stack.destroy"])
    monkeypatch.setattr(mod, "_ensure_vm_task",
                        lambda task_id, title, lifecycle, config: FakeTask(task_id, title))
    monkeypatch.setattr(mod, "_build_prelude_tasks",
                        lambda runner, request, setup, recipe, connectivity,
                        special_handler=None, context_selector=None: [])
    monkeypatch.setattr(mod, "_build_loadgen_body",
                        lambda runner, request, adapter, ctx:
                        [FakeTask("loadgen.run_k6", "Run k6")])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    emitted = []
    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(),
                          adapter=FakeOneVmAdapter(), event_listener=emitted.append)

    running_ids = [e.step.step_id for e in emitted if e.status == "running"]
    assert "vm.loadgen.ensure_running" not in events
    assert "vm.loadgen.ensure_running" not in running_ids
    assert "vm.loadgen.destroy" not in running_ids
    assert events.index("loadgen.run_k6") < events.index("autoscaling.run_k6")
    assert events.index("autoscaling.run_k6") < events.index("vm.stack.destroy")
    assert "prepare:/home/ubuntu" in events
