from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
import shlex

import typer
from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.workflow.context import bind_workflow_sink

from controlplane_tool.release.publish import GHCR_REPOSITORY
from controlplane_tool.cli.execution import build_role_bindings
from controlplane_tool.cli.progress import ConsoleProgressSink
from controlplane_tool.cli.product import _environment
from controlplane_tool.cli.vm_provider import (
    vm_provider_for_environment,
    vm_request_for_role,
)
from controlplane_tool.config import EnvironmentConfig
from controlplane_tool.images.bake import render_bake_json
from controlplane_tool.images.plan import (
    DEFAULT_ARCHITECTURES,
    DEFAULT_REGISTRY,
    ImagePlan,
    build_image_plan,
)
from controlplane_tool.plans._assembly import workflow_from_specs
from controlplane_tool.workspace.paths import default_tool_paths
from workflow_tasks.vm.models import vm_remote_home
from workflow_tasks.vm.orchestrator import VmOrchestrator


_ARCHITECTURES = DEFAULT_ARCHITECTURES
_FLAVORS = ("jvm", "native", "default")
_OFFICIAL_REGISTRY = GHCR_REPOSITORY


def _require_success(result: object, action: str) -> object:
    return_code = getattr(result, "return_code", 0)
    if return_code != 0:
        detail = getattr(result, "stderr", "") or getattr(result, "stdout", "")
        raise RuntimeError(detail or f"{action} failed (exit {return_code})")
    return result


@dataclass
class ImageArchiveTransportTask:
    task_id: str
    title: str
    provider: object
    builder_request: object
    stack_request: object
    images: tuple[str, ...]
    local_archive: Path
    builder_archive: str
    stack_archive: str
    _owns_local_archive: bool = field(default=False, init=False, repr=False)

    def _exec(self, request: object, argv: tuple[str, ...]) -> object:
        result = self.provider.exec_argv(  # type: ignore[attr-defined]
            request,
            argv,
            env=None,
            cwd=None,
            dry_run=False,
        )
        return _require_success(result, shlex.join(argv))

    def _inspect(self, request: object) -> tuple[str, ...]:
        result = self._exec(
            request,
            ("docker", "image", "inspect", "--format={{.Id}}", *self.images),
        )
        digests = tuple(
            line.strip() for line in getattr(result, "stdout", "").splitlines()
        )
        if len(digests) != len(self.images):
            raise RuntimeError(
                f"expected {len(self.images)} image digests, got {len(digests)}"
            )
        return digests

    def _cleanup(self) -> list[str]:
        errors: list[str] = []
        for request, archive in (
            (self.builder_request, self.builder_archive),
            (self.stack_request, self.stack_archive),
        ):
            try:
                self._exec(request, ("rm", "-f", archive))
            except Exception as exc:
                errors.append(str(exc))
        if self._owns_local_archive:
            try:
                self.local_archive.unlink(missing_ok=True)
            except OSError as exc:
                errors.append(str(exc))
            finally:
                self._owns_local_archive = False
        return errors

    def run(self) -> None:
        self.local_archive.parent.mkdir(parents=True, exist_ok=True)
        try:
            self.local_archive.touch(exist_ok=False)
        except FileExistsError:
            raise FileExistsError(
                f"refusing to overwrite pre-existing archive: {self.local_archive}"
            ) from None
        self._owns_local_archive = True
        main_error: BaseException | None = None
        try:
            expected = self._inspect(self.builder_request)
            self._exec(
                self.builder_request,
                ("docker", "save", "--output", self.builder_archive, *self.images),
            )
            result = self.provider.transfer_from(  # type: ignore[attr-defined]
                self.builder_request,
                source=self.builder_archive,
                destination=self.local_archive,
            )
            _require_success(result, "image archive download")
            stack_archive_parent = self.stack_archive.rsplit("/", 1)[0]
            self._exec(
                self.stack_request,
                ("mkdir", "-p", stack_archive_parent),
            )
            result = self.provider.transfer_to(  # type: ignore[attr-defined]
                self.stack_request,
                source=self.local_archive,
                destination=self.stack_archive,
            )
            _require_success(result, "image archive upload")
            self._exec(
                self.stack_request,
                ("docker", "load", "--input", self.stack_archive),
            )
            actual = self._inspect(self.stack_request)
            if actual != expected:
                raise RuntimeError("image digest mismatch after transport")
        except BaseException as exc:
            main_error = exc

        cleanup_errors = self._cleanup()
        if main_error is not None:
            if cleanup_errors:
                raise RuntimeError(
                    f"{main_error}\n\nCleanup errors:\n" + "\n".join(cleanup_errors)
                ) from main_error
            raise main_error
        if cleanup_errors:
            raise RuntimeError("Cleanup failed:\n" + "\n".join(cleanup_errors))


@dataclass
class _BakeFileStageTask:
    task_id: str
    title: str
    provider: object
    request: object
    source: Path
    destination: str

    def run(self) -> None:
        parent = self.destination.rsplit("/", 1)[0]
        result = self.provider.exec_argv(  # type: ignore[attr-defined]
            self.request,
            ("mkdir", "-p", parent),
            env=None,
            cwd=None,
            dry_run=False,
        )
        _require_success(result, "create remote Bake directory")
        result = self.provider.transfer_to(  # type: ignore[attr-defined]
            self.request,
            source=self.source,
            destination=self.destination,
        )
        _require_success(result, "stage Bake file")


@dataclass
class _RemoteFileCleanupTask:
    task_id: str
    title: str
    provider: object
    request: object
    path: str

    def run(self) -> None:
        result = self.provider.exec_argv(  # type: ignore[attr-defined]
            self.request,
            ("rm", "-f", self.path),
            env=None,
            cwd=None,
            dry_run=False,
        )
        _require_success(result, "clean remote Bake file")


def _selected(values: Sequence[str], allowed: Sequence[str], name: str) -> tuple[str, ...]:
    requested = set(values)
    unknown = sorted(requested - set(allowed))
    if unknown:
        raise typer.BadParameter(f"unknown image {name}: {', '.join(unknown)}")
    return tuple(value for value in allowed if not values or value in requested)


def _validate_builder_role(environment: EnvironmentConfig, builder_role: str) -> None:
    if builder_role not in {"stack", "loadgen"}:
        raise typer.BadParameter("builder role must be stack or loadgen")
    if builder_role == "loadgen" and "loadgen" not in environment.roles:
        raise typer.BadParameter("loadgen builder requires a loadgen environment role")


def _validate_portable_registry(registry: str) -> None:
    if registry.rstrip("/") == _OFFICIAL_REGISTRY:
        raise typer.BadParameter(
            "official GHCR registry is reserved for release promotion"
        )


def _prepare_plan(
    version: str,
    *,
    registry: str,
    selectors: Sequence[str],
    architectures: Sequence[str],
    flavors: Sequence[str],
    run_dir: Path | None,
) -> tuple[ImagePlan, Path]:
    paths = default_tool_paths()
    selected_architectures = _selected(architectures, _ARCHITECTURES, "architecture")
    selected_flavors = _selected(flavors, _FLAVORS, "flavor")
    try:
        plan = build_image_plan(
            paths.workspace_root,
            version,
            registry=registry,
            selectors=selectors,
            architectures=selected_architectures,  # type: ignore[arg-type]
            flavors=selected_flavors,  # type: ignore[arg-type]
        )
    except (FileNotFoundError, ValueError) as exc:
        raise typer.BadParameter(str(exc)) from None
    if not plan.cells:
        raise typer.BadParameter("selection produced no image cells")
    destination = run_dir or paths.runs_dir / "images" / plan.version
    destination.mkdir(parents=True, exist_ok=True)
    bake_file = destination / "docker-bake.json"
    bake_file.write_text(
        render_bake_json(plan, flavors=selected_flavors),  # type: ignore[arg-type]
        encoding="utf-8",
    )
    return plan, bake_file


def _plan_specs(plan: ImagePlan, bake_file: Path, *, builder_role: str) -> tuple[CommandTaskSpec, ...]:
    specs: list[CommandTaskSpec] = []
    seen_prerequisites: set[str] = set()
    for cell in plan.bake_cells:
        command = cell.prerequisite_command
        if command is None or cell.target.name in seen_prerequisites:
            continue
        seen_prerequisites.add(cell.target.name)
        specs.append(
            CommandTaskSpec(
                task_id=f"images.prepare.{cell.target.name}",
                summary=f"Prepare {cell.target.name} JVM image",
                argv=command,
                role=builder_role,  # type: ignore[arg-type]
            )
        )
    for architecture in DEFAULT_ARCHITECTURES:
        if any(cell.architecture == architecture for cell in plan.bake_cells):
            specs.append(
                CommandTaskSpec(
                    task_id=f"images.bake.{architecture}",
                    summary=f"Render {architecture} Docker image build",
                    argv=(
                        "docker",
                        "buildx",
                        "bake",
                        "--file",
                        str(bake_file),
                        "--print",
                        f"docker-{architecture}",
                    ),
                    role=builder_role,  # type: ignore[arg-type]
                )
            )
    specs.extend(
        CommandTaskSpec(
            task_id=f"images.gradle.{cell.target.name}.{cell.architecture}",
            summary=f"Build {cell.target.name} {cell.architecture} native image",
            argv=cell.gradle_command or (),
            role=builder_role,  # type: ignore[arg-type]
        )
        for cell in plan.gradle_cells
    )
    return tuple(specs)


def _build_specs(
    plan: ImagePlan,
    bake_file: Path,
    *,
    builder_role: str,
    verify_role: str,
    push: bool,
) -> tuple[CommandTaskSpec, ...]:
    plan_specs = _plan_specs(plan, bake_file, builder_role=builder_role)
    specs = [
        CommandTaskSpec(
            task_id=spec.task_id,
            summary=spec.summary.replace("Render", "Build"),
            argv=(
                (*spec.argv[:-2], "--load", spec.argv[-1])
                if spec.task_id.startswith("images.bake.")
                else spec.argv
            ),
            role=spec.role,
        )
        for spec in plan_specs
    ]
    images = tuple(cell.image for cell in plan.cells)
    specs.append(
        CommandTaskSpec(
            task_id="images.verify",
            summary=f"Verify {len(images)} logical image digests",
            argv=("docker", "image", "inspect", "--format={{.Id}}", *images),
            role=verify_role,  # type: ignore[arg-type]
        )
    )
    if push:
        specs.extend(_push_specs(plan, role=verify_role))
    return tuple(specs)


def _push_specs(plan: ImagePlan, *, role: str) -> tuple[CommandTaskSpec, ...]:
    return tuple(
        CommandTaskSpec(
            task_id=f"images.push.{cell.target.name}.{cell.architecture}.{cell.flavor}",
            summary=f"Push {cell.image}",
            argv=("docker", "push", cell.image),
            role=role,  # type: ignore[arg-type]
        )
        for cell in plan.cells
    )


def _provider(environment: EnvironmentConfig, repo_root: Path) -> object:
    if environment.provider in {"azure", "proxmox"}:
        return vm_provider_for_environment(environment, repo_root)
    return VmOrchestrator(repo_root)


def _remote_run_dir(request: object, version: str) -> str:
    return f"{vm_remote_home(request)}/nanofaas/.nanofaas-runs/images/{version}"  # type: ignore[arg-type]


def build_image_workflow(
    plan: ImagePlan,
    bake_file: Path,
    *,
    environment: EnvironmentConfig,
    builder_role: str,
    repo_root: Path,
    push: bool,
    provider: object | None = None,
):
    if builder_role == "loadgen" and "loadgen" not in environment.roles:
        raise ValueError("loadgen builder requires a loadgen environment role")
    if environment.provider == "local":
        bindings, _ = build_role_bindings(environment, repo_root=repo_root)
        return workflow_from_specs(
            _build_specs(
                plan,
                bake_file,
                builder_role=builder_role,
                verify_role="stack",
                push=push,
            ),
            bindings,
            cwd=repo_root,
        )

    orchestrator = provider or _provider(environment, repo_root)
    bindings, _ = build_role_bindings(
        environment,
        vm_provider=orchestrator,
        repo_root=repo_root,
    )
    builder_request = vm_request_for_role(environment, builder_role)  # type: ignore[arg-type]
    stack_request = vm_request_for_role(environment, "stack")
    builder_run_dir = _remote_run_dir(builder_request, plan.version)
    remote_bake_file = f"{builder_run_dir}/docker-bake.json"
    all_specs = _build_specs(
        plan,
        Path(remote_bake_file),
        builder_role=builder_role,
        verify_role="stack",
        push=push,
    )
    if builder_role == "stack":
        command_tasks = workflow_from_specs(all_specs, bindings, cwd=repo_root).tasks
    else:
        build_specs = tuple(spec for spec in all_specs if spec.task_id != "images.verify")
        build_specs = tuple(
            spec for spec in build_specs if not spec.task_id.startswith("images.push.")
        )
        command_tasks = workflow_from_specs(build_specs, bindings, cwd=repo_root).tasks
        stack_run_dir = _remote_run_dir(stack_request, plan.version)
        command_tasks.append(
            ImageArchiveTransportTask(
                task_id="images.transport",
                title=f"Transfer and verify {len(plan.cells)} logical image digests",
                provider=orchestrator,
                builder_request=builder_request,
                stack_request=stack_request,
                images=tuple(cell.image for cell in plan.cells),
                local_archive=bake_file.parent / "images.tar",
                builder_archive=f"{builder_run_dir}/images.tar",
                stack_archive=f"{stack_run_dir}/images.tar",
            )
        )
        if push:
            command_tasks.extend(
                workflow_from_specs(
                    _push_specs(plan, role="stack"),
                    bindings,
                    cwd=repo_root,
                ).tasks
            )

    workflow = workflow_from_specs((), bindings, cwd=repo_root)
    workflow.tasks = [
        _BakeFileStageTask(
            task_id="images.bake.stage",
            title="Stage generated Bake file on builder",
            provider=orchestrator,
            request=builder_request,
            source=bake_file,
            destination=remote_bake_file,
        ),
        *command_tasks,
    ]
    workflow.cleanup_tasks = [
        _RemoteFileCleanupTask(
            task_id="images.bake.cleanup",
            title="Remove generated Bake file from builder",
            provider=orchestrator,
            request=builder_request,
            path=remote_bake_file,
        )
    ]
    return workflow


def _render_specs(specs: Sequence[CommandTaskSpec]) -> None:
    for spec in specs:
        typer.echo(f"{spec.task_id}  {shlex.join(spec.argv)}")


def install_image_commands(app: typer.Typer) -> None:
    images = typer.Typer(help="Plan and build the platform image matrix.")

    @images.command("plan")
    def plan_command(
        version: str = typer.Argument(...),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        registry: str = typer.Option(DEFAULT_REGISTRY, "--registry"),
        targets: list[str] | None = typer.Option(None, "--target"),
        architectures: list[str] | None = typer.Option(None, "--arch"),
        flavors: list[str] | None = typer.Option(None, "--flavor"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
        builder_role: str = typer.Option("stack", "--builder-role"),
    ) -> None:
        environment_config = _environment(environment)
        _validate_builder_role(environment_config, builder_role)
        _validate_portable_registry(registry)
        plan, bake_file = _prepare_plan(
            version,
            registry=registry,
            selectors=targets or (),
            architectures=architectures or (),
            flavors=flavors or (),
            run_dir=run_dir,
        )
        _render_specs(_plan_specs(plan, bake_file, builder_role=builder_role))

    @images.command("build")
    def build_command(
        version: str = typer.Argument(...),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        registry: str = typer.Option(DEFAULT_REGISTRY, "--registry"),
        targets: list[str] | None = typer.Option(None, "--target"),
        architectures: list[str] | None = typer.Option(None, "--arch"),
        flavors: list[str] | None = typer.Option(None, "--flavor"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
        builder_role: str = typer.Option("stack", "--builder-role"),
        push: bool = typer.Option(False, "--push"),
        dry_run: bool = typer.Option(False, "--dry-run"),
    ) -> None:
        environment_config = _environment(environment)
        _validate_builder_role(environment_config, builder_role)
        _validate_portable_registry(registry)
        if push and not registry.startswith(("localhost:", "127.0.0.1:")):
            raise typer.BadParameter("--push requires a stack-local registry")
        plan, bake_file = _prepare_plan(
            version,
            registry=registry,
            selectors=targets or (),
            architectures=architectures or (),
            flavors=flavors or (),
            run_dir=run_dir,
        )
        if dry_run:
            _render_specs(
                _build_specs(
                    plan,
                    bake_file,
                    builder_role=builder_role,
                    verify_role="stack",
                    push=push,
                )
            )
            return
        workflow = build_image_workflow(
            plan,
            bake_file,
            environment=environment_config,
            builder_role=builder_role,
            repo_root=default_tool_paths().workspace_root,
            push=push,
        )
        with bind_workflow_sink(ConsoleProgressSink()):
            workflow.run()

    app.add_typer(images, name="images")
