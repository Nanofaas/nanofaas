from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
from typing import TypedDict

from controlplane_tool.orchestation.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.orchestation.prefect_runtime import run_local_flow


@dataclass(slots=True)
class PrefectDeploymentSpec:
    flow_id: str
    name: str
    entrypoint: str
    task_ids: list[str]
    work_pool: str | None = None
    tags: list[str] = field(default_factory=list)
    parameters: dict[str, object] = field(default_factory=dict)


class _KnownDeploymentConfig(TypedDict):
    parameters: dict[str, object]


_KNOWN_DEPLOYMENTS: dict[str, _KnownDeploymentConfig] = {
    "building.building": {
        "parameters": {
            "flow_name": "building.building",
            "profile": "core",
            "modules": None,
            "extra_gradle_args": [],
            "dry_run": False,
        }
    }
}


def _find_nonzero_return_code(result: object) -> int | None:
    if isinstance(result, (list, tuple)):
        for item in result:
            code = _find_nonzero_return_code(item)
            if code is not None:
                return code
        return None

    return_code = getattr(result, "return_code", None)
    if isinstance(return_code, int) and return_code != 0:
        return return_code
    return None


def run_deployment_flow(*, flow_name: str, **parameters: object) -> object:
    flow = resolve_flow_definition(flow_name, **parameters)
    flow_result = run_local_flow(flow.flow_id, flow.run)
    if flow_result.status != "completed":
        raise RuntimeError(flow_result.error or f"{flow.flow_id} failed")
    return_code = _find_nonzero_return_code(flow_result.result)
    if return_code is not None:
        raise RuntimeError(f"{flow.flow_id} failed with exit code {return_code}")
    return flow_result.result


def build_prefect_deployment(
    flow_id: str,
    *,
    enabled: bool,
    work_pool: str | None = None,
    tags: list[str] | None = None,
) -> PrefectDeploymentSpec | None:
    if not enabled:
        return None

    config = _KNOWN_DEPLOYMENTS.get(flow_id)
    if config is None:
        raise ValueError(f"Unsupported Prefect deployment flow: {flow_id}")
    parameters = deepcopy(config["parameters"])
    flow_name = parameters.get("flow_name")
    if not isinstance(flow_name, str):
        raise ValueError(f"Prefect deployment {flow_id} has invalid flow_name")
    task_ids = resolve_flow_task_ids(flow_name)

    return PrefectDeploymentSpec(
        flow_id=flow_id,
        name=flow_id.replace(".", "-").replace("_", "-"),
        entrypoint="controlplane_tool.orchestation.prefect_deployments:run_deployment_flow",
        task_ids=task_ids,
        work_pool=work_pool,
        tags=list(tags or []),
        parameters=parameters,
    )
