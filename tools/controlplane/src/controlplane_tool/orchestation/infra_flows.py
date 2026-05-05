from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
import json
from pathlib import Path
import time

from controlplane_tool.orchestation.adapters import ShellCommandAdapter
from controlplane_tool.building.gradle_executor import GradleCommandExecutor
from controlplane_tool.building.tasks import (
    CommandExecutionResult,
    api_tests_task,
    build_image_task,
    compile_task,
    preflight_task,
    mockk8s_tests_task,
    run_gradle_action_task,
)
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.loadtest.loadtest_runner import LoadtestRunner
from controlplane_tool.core.models import Profile
from controlplane_tool.app.paths import default_tool_paths
from controlplane_tool.infra.runtimes import default_registry_url
from controlplane_tool.orchestation.prefect_models import LocalFlowDefinition
from controlplane_tool.loadtest.report import render_report
from controlplane_tool.core.run_models import RunResult, StepResult
from controlplane_tool.scenario.scenario_loader import load_scenario_file, resolve_scenario_spec
from controlplane_tool.scenario.scenario_models import ScenarioSpec
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.infra.vm.vm_tasks import (
    configure_k3s_registry_task,
    ensure_registry_container_task,
    ensure_vm_running_task,
    inspect_vm_task,
    provision_base_task,
    provision_k3s_task,
    sync_project_task,
    teardown_vm_task,
)


def vm_flow_task_ids(flow_id: str) -> list[str]:
    if flow_id == "vm.registry":
        return ["registry.ensure_container", "k3s.configure_registry"]
    return [flow_id]


def gradle_action_task_ids(action: str) -> list[str]:
    return [f"building.{action}"]


def pipeline_task_ids(profile: Profile) -> list[str]:
    task_ids = [
        "preflight.check",
        "building.compile",
        "images.build_control_plane",
        "tests.run_api",
        "tests.run_mockk8s",
    ]
    if profile.tests.enabled and profile.tests.metrics:
        task_ids.append("loadtest.run")
    return task_ids


def build_vm_flow(
    flow_id: str,
    *,
    request: VmRequest,
    repo_root: Path,
    dry_run: bool,
    remote_dir: str | None = None,
    install_helm: bool = False,
    helm_version: str = "3.16.4",
    kubeconfig_path: str | None = None,
    k3s_version: str | None = None,
    registry: str = "",
    container_name: str = "nanofaas-e2e-registry",
    orchestrator: VmOrchestrator | None = None,
) -> LocalFlowDefinition[object]:
    active_orchestrator = orchestrator or VmOrchestrator(repo_root)

    def _run() -> object:
        if flow_id == "vm.up":
            return ensure_vm_running_task(orchestrator=active_orchestrator, request=request, dry_run=dry_run)
        if flow_id == "vm.sync":
            return sync_project_task(
                orchestrator=active_orchestrator,
                request=request,
                remote_dir=remote_dir,
                dry_run=dry_run,
            )
        if flow_id == "vm.provision_base":
            return provision_base_task(
                orchestrator=active_orchestrator,
                request=request,
                install_helm=install_helm,
                helm_version=helm_version,
                dry_run=dry_run,
            )
        if flow_id == "vm.provision_k3s":
            return provision_k3s_task(
                orchestrator=active_orchestrator,
                request=request,
                kubeconfig_path=kubeconfig_path,
                k3s_version=k3s_version,
                dry_run=dry_run,
            )
        if flow_id == "vm.registry":
            ensure_result = ensure_registry_container_task(
                orchestrator=active_orchestrator,
                request=request,
                registry=registry or default_registry_url(),
                container_name=container_name,
                dry_run=dry_run,
            )
            results = [ensure_result]
            if ensure_result.return_code == 0:
                results.append(
                    configure_k3s_registry_task(
                        orchestrator=active_orchestrator,
                        request=request,
                        registry=registry or default_registry_url(),
                        dry_run=dry_run,
                    )
                )
            return results
        if flow_id == "vm.down":
            return teardown_vm_task(orchestrator=active_orchestrator, request=request, dry_run=dry_run)
        if flow_id == "vm.inspect":
            return inspect_vm_task(orchestrator=active_orchestrator, request=request, dry_run=dry_run)
        raise ValueError(f"Unsupported VM flow: {flow_id}")

    return LocalFlowDefinition(flow_id=flow_id, task_ids=vm_flow_task_ids(flow_id), run=_run)


def build_gradle_action_flow(
    *,
    action: str,
    profile: str,
    modules: str | None,
    extra_gradle_args: list[str],
    dry_run: bool,
    executor: object | None = None,
) -> LocalFlowDefinition[CommandExecutionResult]:
    active_executor = executor or GradleCommandExecutor()
    flow_id = f"building.{action}"
    return LocalFlowDefinition(
        flow_id=flow_id,
        task_ids=gradle_action_task_ids(action),
        run=lambda: run_gradle_action_task(
            executor=active_executor,
            action=action,
            profile=profile,
            modules=modules,
            extra_gradle_args=extra_gradle_args,
            dry_run=dry_run,
        ),
    )


def _loadtest_request(profile: Profile) -> LoadtestRequest:
    if profile.scenario.scenario_file:
        scenario = load_scenario_file(Path(profile.scenario.scenario_file))
    else:
        scenario = resolve_scenario_spec(
            ScenarioSpec(
                name=f"{profile.name}-loadtest",
                base_scenario=profile.scenario.base_scenario or "k3s-junit-curl",
                runtime=profile.control_plane.implementation,
                function_preset=profile.scenario.function_preset or "metrics-smoke",
                functions=list(profile.scenario.functions),
                namespace=profile.scenario.namespace,
                local_registry=profile.scenario.local_registry,
            )
        )

    return LoadtestRequest(
        name=profile.name,
        profile=profile,
        scenario=scenario,
        load_profile=resolve_load_profile(profile.tests.load_profile),
        metrics_gate=MetricsGate(
            mode=profile.loadtest.metrics_gate_mode,
            required_metrics=list(profile.metrics.required),
        ),
    )


def build_pipeline_flow(
    profile: Profile,
    *,
    adapter: object | None = None,
    runs_root: Path | None = None,
) -> LocalFlowDefinition[RunResult]:
    active_adapter = adapter or ShellCommandAdapter()
    root = runs_root or default_tool_paths().runs_dir
    task_ids = pipeline_task_ids(profile)

    def _run_step(name: str, fn: object, run_dir: Path) -> StepResult:
        start = time.time()
        ok, detail = fn(adapter=active_adapter, profile=profile, run_dir=run_dir)
        duration_ms = int((time.time() - start) * 1000)
        return StepResult(
            name=name,
            status="passed" if ok else "failed",
            detail=detail,
            duration_ms=duration_ms,
        )

    def _summary_payload(result: RunResult) -> dict[str, object]:
        series_path = result.run_dir / "metrics" / "series.json"
        metrics: dict[str, object] = {}
        if series_path.exists():
            try:
                metrics = json.loads(series_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                metrics = {}
        return {
            "profile_name": result.profile_name,
            "run_dir": str(result.run_dir),
            "final_status": result.final_status,
            "steps": [asdict(step) for step in result.steps],
            "metrics": metrics,
        }

    def _supports_structured_loadtest(adapter: object) -> bool:
        required_methods = (
            "bootstrap_loadtest",
            "run_loadtest_k6",
            "evaluate_metrics_gate",
            "cleanup_loadtest",
        )
        return all(callable(getattr(adapter, name, None)) for name in required_methods)

    def _finalize(result: RunResult) -> RunResult:
        payload = _summary_payload(result)
        (result.run_dir / "summary.json").write_text(
            json.dumps(payload, indent=2),
            encoding="utf-8",
        )
        render_report(summary=payload, output_dir=result.run_dir)
        return result

    def _run() -> RunResult:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        run_dir = root / f"{timestamp}-{profile.name}"
        run_dir.mkdir(parents=True, exist_ok=True)

        steps: list[StepResult] = []
        missing = preflight_task(adapter=active_adapter, profile=profile)
        if missing:
            result = RunResult(
                profile_name=profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=[
                    StepResult(
                        name="preflight",
                        status="failed",
                        detail=f"missing tools: {', '.join(missing)}",
                        duration_ms=0,
                    )
                ],
            )
            return _finalize(result)

        steps.append(StepResult(name="preflight", status="passed", detail="ok", duration_ms=0))
        compile_step = _run_step("compile", compile_task, run_dir)
        steps.append(compile_step)
        if compile_step.status == "failed":
            return _finalize(
                RunResult(profile_name=profile.name, run_dir=run_dir, final_status="failed", steps=steps)
            )

        image_step = _run_step("docker_image", build_image_task, run_dir)
        steps.append(image_step)
        if image_step.status == "failed":
            return _finalize(
                RunResult(profile_name=profile.name, run_dir=run_dir, final_status="failed", steps=steps)
            )

        if profile.tests.enabled and profile.tests.api:
            steps.append(_run_step("test_api", api_tests_task, run_dir))
        else:
            steps.append(StepResult(name="test_api", status="skipped", detail="disabled", duration_ms=0))

        if profile.tests.enabled and profile.tests.e2e_mockk8s:
            steps.append(_run_step("test_e2e_mockk8s", mockk8s_tests_task, run_dir))
        else:
            steps.append(
                StepResult(name="test_e2e_mockk8s", status="skipped", detail="disabled", duration_ms=0)
            )

        if profile.tests.enabled and profile.tests.metrics:
            start = time.time()
            if _supports_structured_loadtest(active_adapter):
                loadtest_result = LoadtestRunner(adapter=active_adapter).run(
                    _loadtest_request(profile),
                    runs_root=run_dir.parent,
                )
                detail = f"loadtest runner: {loadtest_result.final_status} ({loadtest_result.run_dir})"
                step_status = "passed" if loadtest_result.final_status == "passed" else "failed"
            else:
                ok, detail = active_adapter.run_metrics_tests(profile, run_dir)
                step_status = "passed" if ok else "failed"
            steps.append(
                StepResult(
                    name="test_metrics_prometheus_k6",
                    status=step_status,
                    detail=detail,
                    duration_ms=int((time.time() - start) * 1000),
                )
            )
        else:
            steps.append(
                StepResult(
                    name="test_metrics_prometheus_k6",
                    status="skipped",
                    detail="disabled",
                    duration_ms=0,
                )
            )

        final_status = "failed" if any(step.status == "failed" for step in steps) else "passed"
        return _finalize(
            RunResult(
                profile_name=profile.name,
                run_dir=run_dir,
                final_status=final_status,
                steps=steps,
            )
        )

    return LocalFlowDefinition(flow_id="infra.pipeline", task_ids=task_ids, run=_run)
