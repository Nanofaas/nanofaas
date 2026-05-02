from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field

from controlplane_tool.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.prefect_runtime import run_local_flow


@dataclass(slots=True)
class PrefectDeploymentSpec:
    flow_id: str
    name: str
    entrypoint: str
    task_ids: list[str]
    work_pool: str | None = None
    tags: list[str] = field(default_factory=list)
    parameters: dict[str, object] = field(default_factory=dict)


_KNOWN_DEPLOYMENTS: dict[str, dict[str, object]] = {
    "build.build": {
        "parameters": {
            "flow_name": "build.build",
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
    task_ids = resolve_flow_task_ids(str(parameters["flow_name"]))

    return PrefectDeploymentSpec(
        flow_id=flow_id,
        name=flow_id.replace(".", "-").replace("_", "-"),
        entrypoint="controlplane_tool.prefect_deployments:run_deployment_flow",
        task_ids=task_ids,
        work_pool=work_pool,
        tags=list(tags or []),
        parameters=parameters,
    )
