from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile


TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT = 30080
TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT = 30081
TWO_VM_PROMETHEUS_NODE_PORT = 30090
TWO_VM_REMOTE_DIR_NAME = "two-vm-loadtest"


@dataclass(frozen=True, slots=True)
class TwoVmRemotePaths:
    root_dir: str
    scripts_dir: str
    payloads_dir: str
    results_dir: str
    script_path: str
    summary_path: str
    payload_path: str | None = None


def two_vm_control_plane_url(vm_request: VmRequest, *, host: str | None = None) -> str:
    resolved_host = host
    if resolved_host is None:
        if vm_request.lifecycle == "external":
            resolved_host = str(vm_request.host)
        else:
            resolved_host = f"<multipass-ip:{vm_request.name or 'nanofaas-e2e'}>"
    return f"http://{resolved_host}:{TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT}"


def two_vm_prometheus_url(vm_request: VmRequest, *, host: str | None = None) -> str:
    resolved_host = host
    if resolved_host is None:
        if vm_request.lifecycle == "external":
            resolved_host = str(vm_request.host)
        else:
            resolved_host = f"<multipass-ip:{vm_request.name or 'nanofaas-e2e'}>"
    return f"http://{resolved_host}:{TWO_VM_PROMETHEUS_NODE_PORT}"


def two_vm_remote_paths(remote_home: str, *, payload_name: str | None = None) -> TwoVmRemotePaths:
    root_dir = f"{remote_home}/{TWO_VM_REMOTE_DIR_NAME}"
    scripts_dir = f"{root_dir}/scripts"
    payloads_dir = f"{root_dir}/payloads"
    results_dir = f"{root_dir}/results"
    return TwoVmRemotePaths(
        root_dir=root_dir,
        scripts_dir=scripts_dir,
        payloads_dir=payloads_dir,
        results_dir=results_dir,
        script_path=f"{scripts_dir}/script.js",
        summary_path=f"{results_dir}/k6-summary.json",
        payload_path=f"{payloads_dir}/{payload_name}" if payload_name else None,
    )


def two_vm_target_function(request: Any) -> str:
    resolved = getattr(request, "resolved_scenario", None)
    if resolved is None:
        functions = getattr(request, "functions", [])
        return functions[0] if functions else "word-stats-java"
    if resolved.load.targets:
        return resolved.load.targets[0]
    if resolved.function_keys:
        return resolved.function_keys[0]
    return "word-stats-java"


def two_vm_load_stages(request: Any) -> tuple[tuple[str, int], ...]:
    profile_name = "quick"
    resolved = getattr(request, "resolved_scenario", None)
    if resolved is not None and resolved.load.load_profile_name:
        profile_name = resolved.load.load_profile_name
    profile = resolve_load_profile(profile_name)
    return tuple((stage.duration, stage.target) for stage in profile.stages)
