from __future__ import annotations


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


def build_deploy_env(
    *,
    vm_name: str,
    cpus: str,
    memory: str,
    disk: str,
    namespace: str,
    keep_vm: bool,
    tag: str,
    selected_modules: list[str],
) -> dict[str, str]:
    return {
        "VM_NAME": vm_name,
        "CPUS": cpus,
        "MEMORY": memory,
        "DISK": disk,
        "NAMESPACE": namespace,
        "KEEP_VM": "true" if keep_vm else "false",
        "TAG": tag,
        "CONTROL_PLANE_NATIVE_BUILD": "true",
        "CONTROL_PLANE_MODULES": build_control_plane_modules_selector(selected_modules),
    }
