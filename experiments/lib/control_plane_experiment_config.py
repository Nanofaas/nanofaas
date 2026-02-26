from __future__ import annotations

from pathlib import Path
import re


_MODULE_DEP_RE = re.compile(r"implementation\s+project\(':control-plane-modules:([^']+)'\)")


def normalize_module_selection(available: list[str], selected: list[str]) -> list[str]:
    normalized_available = [item.strip() for item in available if item.strip()]
    available_set = set(normalized_available)

    deduped_selected: list[str] = []
    seen: set[str] = set()
    for raw in selected:
        module = raw.strip()
        if not module or module in seen:
            continue
        seen.add(module)
        deduped_selected.append(module)

    unknown = [module for module in deduped_selected if module not in available_set]
    if unknown:
        raise ValueError(f"unknown control-plane modules: {', '.join(unknown)}")

    return [module for module in normalized_available if module in set(deduped_selected)]


def build_control_plane_modules_selector(selected_modules: list[str]) -> str:
    if not selected_modules:
        return "none"
    return ",".join(selected_modules)


def discover_module_dependencies(modules_root: Path) -> dict[str, list[str]]:
    module_deps: dict[str, list[str]] = {}
    for entry in sorted(modules_root.iterdir()):
        if not entry.is_dir():
            continue
        build_file = entry / "build.gradle"
        if not build_file.is_file():
            continue
        found = _MODULE_DEP_RE.findall(build_file.read_text(encoding="utf-8"))
        deduped = list(dict.fromkeys(found))
        module_deps[entry.name] = deduped
    return module_deps


def resolve_module_selection_with_dependencies(
    *,
    available_modules: list[str],
    selected_modules: list[str],
    module_dependencies: dict[str, list[str]],
) -> list[str]:
    ordered_selected = normalize_module_selection(available_modules, selected_modules)
    if not ordered_selected:
        return []

    available_set = set(available_modules)
    closure: set[str] = set(ordered_selected)
    queue = list(ordered_selected)

    while queue:
        module = queue.pop(0)
        for dep in module_dependencies.get(module, []):
            if dep not in available_set:
                raise ValueError(f"module '{module}' depends on missing module '{dep}'")
            if dep in closure:
                continue
            closure.add(dep)
            queue.append(dep)

    return [module for module in available_modules if module in closure]


def split_module_selection_details(
    *,
    resolved_modules: list[str],
    explicitly_selected_modules: list[str],
) -> tuple[list[str], list[str]]:
    explicit_set = set(explicitly_selected_modules)
    explicit = [module for module in resolved_modules if module in explicit_set]
    auto_added = [module for module in resolved_modules if module not in explicit_set]
    return explicit, auto_added


def build_deploy_env(
    *,
    vm_name: str,
    cpus: str,
    memory: str,
    disk: str,
    namespace: str,
    keep_vm: bool,
    tag: str,
    control_plane_runtime: str,
    control_plane_native_build: bool,
    control_plane_only: bool,
    host_rebuild_images: bool,
    host_rebuild_image_refs: list[str] | None = None,
    host_java_native_image_refs: list[str] | None = None,
    loadtest_workloads: str,
    loadtest_runtimes: str,
    selected_modules: list[str],
) -> dict[str, str]:
    runtime = control_plane_runtime.strip().lower()
    if runtime not in {"java", "rust"}:
        raise ValueError("control_plane_runtime must be 'java' or 'rust'")
    native_build_enabled = bool(control_plane_native_build) and runtime == "java"
    rebuild_refs = [item.strip() for item in (host_rebuild_image_refs or []) if item.strip()]
    java_native_refs = [item.strip() for item in (host_java_native_image_refs or []) if item.strip()]
    return {
        "VM_NAME": vm_name,
        "CPUS": cpus,
        "MEMORY": memory,
        "DISK": disk,
        "NAMESPACE": namespace,
        "KEEP_VM": "true" if keep_vm else "false",
        "TAG": tag,
        "CONTROL_PLANE_RUNTIME": runtime,
        "CONTROL_PLANE_NATIVE_BUILD": "true" if native_build_enabled else "false",
        "CONTROL_PLANE_BUILD_ON_HOST": "true",
        "CONTROL_PLANE_ONLY": "true" if control_plane_only else "false",
        "HOST_REBUILD_IMAGES": "true" if host_rebuild_images else "false",
        "HOST_REBUILD_IMAGE_REFS": ",".join(rebuild_refs),
        "HOST_JAVA_NATIVE_IMAGE_REFS": ",".join(java_native_refs),
        "LOADTEST_WORKLOADS": loadtest_workloads,
        "LOADTEST_RUNTIMES": loadtest_runtimes,
        "E2E_K3S_HELM_NONINTERACTIVE": "true",
        "CONTROL_PLANE_MODULES": build_control_plane_modules_selector(selected_modules),
    }
