import json
from pathlib import Path
import urllib.request

import yaml
from workflow_tasks.components.container import managed_process_resource
from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.workflows.validate import (
    ValidateFunction,
    ValidateWorkflowRequest,
    validate_cleanup_specs,
    validate_task_specs,
)

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.functions.catalog import FunctionDefinition, resolve_function_definition
from controlplane_tool.plans._assembly import workflow_from_specs


def _container_control_plane(repo_root: Path):
    health_url = "http://127.0.0.1:18081/actuator/health"

    def ready() -> bool:
        try:
            with urllib.request.urlopen(health_url, timeout=1) as response:
                return response.status == 200
        except OSError:
            return False

    return managed_process_resource(
        task_id="container.start.control-plane",
        title="Start container-local control plane",
        argv=(
            "java",
            "-jar",
            str(repo_root / "platform/control-plane/build/libs/app.jar"),
            "--server.port=18080",
            "--management.server.port=18081",
            "--sync-queue.enabled=false",
            "--nanofaas.deployment.default-backend=container-local",
            "--nanofaas.container-local.runtime-adapter=docker",
            "--nanofaas.container-local.bind-host=127.0.0.1",
        ),
        cwd=repo_root,
        ready=ready,
    )


def _function_image(definition: FunctionDefinition) -> str:
    if definition.default_image is None:
        raise ValueError(f"function {definition.key!r} has no image")
    return definition.default_image


def _build_argv(definition: FunctionDefinition, image: str) -> tuple[str, ...]:
    family = definition.family
    runtime = definition.runtime
    if runtime == "java":
        return (
            "./gradlew",
            f":functions:java:{family}:bootBuildImage",
            f"-PfunctionImage={image}",
            "--quiet",
        )
    runtime_dir = {"java-lite": "java", "exec": "bash"}.get(runtime, runtime)
    suffix = "-lite" if runtime == "java-lite" else ""
    return (
        "docker",
        "build",
        "-t",
        image,
        "-f",
        f"functions/{runtime_dir}/{family}{suffix}/Dockerfile",
        ".",
    )


def _function_name(definition: FunctionDefinition) -> str:
    if definition.example_dir is None:
        return definition.key
    manifest = yaml.safe_load(
        (definition.example_dir / "function.yaml").read_text(encoding="utf-8")
    )
    return str(manifest.get("name", definition.key))


def _payload(definition: FunctionDefinition) -> str:
    if definition.default_payload_file is None:
        return '{"input":{}}'
    payload_path = (
        definition.example_dir.parents[2]
        / "tools/controlplane/scenarios/payloads"
        / definition.default_payload_file
        if definition.example_dir is not None
        else None
    )
    if payload_path is None or not payload_path.exists():
        return '{"input":{}}'
    payload = json.loads(payload_path.read_text(encoding="utf-8"))
    return json.dumps({"input": payload}, separators=(",", ":"))


def _resolve_function(config: ScenarioConfig, key: str) -> ValidateFunction:
    definition = resolve_function_definition(key)
    image = _function_image(definition)
    resource = config.resources.get(key)
    return ValidateFunction(
        key=key,
        name=_function_name(definition),
        image=image,
        build_argv=_build_argv(definition, image),
        payload=_payload(definition),
        resources=(
            resource.model_dump(by_alias=True, exclude_none=True) if resource is not None else None
        ),
    )


def build_validate_plan(
    config: ScenarioConfig,
    bindings: RoleBindings,
    *,
    repo_root: Path | None = None,
) -> Workflow:
    if config.workflow != "validate" or config.backend is None:
        raise ValueError("validate plan requires a validate scenario with a backend")
    request = ValidateWorkflowRequest(
        backend=config.backend,
        build=config.build,
        functions=tuple(_resolve_function(config, key) for key in config.functions),
        additional_modules=("async-queue", "sync-queue") if config.backend == "k8s" else (),
    )
    root = repo_root or Path.cwd()
    specs = validate_task_specs(request)
    cleanup_tasks = workflow_from_specs(validate_cleanup_specs(request), bindings, cwd=root).tasks
    if config.backend != "container":
        workflow = workflow_from_specs(specs, bindings, cwd=root)
        workflow.cleanup_tasks = cleanup_tasks
        return workflow

    commands = workflow_from_specs(
        tuple(spec for spec in specs if spec.task_id != "container.start.control-plane"),
        bindings,
        cwd=root,
    )
    start_index = next(
        index for index, spec in enumerate(specs) if spec.task_id == "container.start.control-plane"
    )
    commands.tasks.insert(start_index, _container_control_plane(root))
    commands.cleanup_tasks = cleanup_tasks
    return commands
