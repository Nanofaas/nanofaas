from __future__ import annotations

import shlex
from pathlib import Path

from controlplane_tool.infra.ops.helm_ops import HelmOps
from controlplane_tool.building.image_ops import ImageOps


def _shell_join(command: list[str]) -> str:
    return shlex.join(command)


def _render_remote_script(
    *,
    remote_dir: str,
    commands: list[list[str] | str],
) -> str:
    rendered = [
        command if isinstance(command, str) else _shell_join(command)
        for command in commands
    ]
    return f"cd {shlex.quote(remote_dir)} && " + " && ".join(rendered)


def _with_sudo(command: list[str], *, sudo: bool) -> list[str]:
    return ["sudo", *command] if sudo else command


def _with_kubeconfig(
    command: list[str],
    *,
    kubeconfig_path: str | None,
) -> list[str] | str:
    if kubeconfig_path is None:
        return command
    return f"KUBECONFIG={shlex.quote(kubeconfig_path)} {_shell_join(command)}"


def build_core_images_vm_script(
    *,
    remote_dir: str,
    control_image: str,
    runtime_image: str,
    runtime: str = "java",
    mode: str = "docker",
    sudo: bool = True,
    build_jars: bool = False,
) -> str:
    image_ops = ImageOps(Path(remote_dir))
    commands: list[list[str] | str] = []

    if mode == "gradle_bootbuildimage":
        commands.append(
            [
                "./gradlew",
                ":control-plane:bootBuildImage",
                ":function-runtime:bootBuildImage",
                f"-PcontrolPlaneImage={control_image}",
                f"-PfunctionRuntimeImage={runtime_image}",
                "--no-daemon",
            ]
        )
    else:
        if build_jars:
            if runtime == "rust":
                commands.append(
                    [
                        "./gradlew",
                        ":function-runtime:bootJar",
                        "--no-daemon",
                        "-q",
                    ]
                )
            else:
                commands.append(
                    [
                        "./gradlew",
                        ":control-plane:bootJar",
                        ":function-runtime:bootJar",
                        "--no-daemon",
                        "-q",
                    ]
                )
        if runtime == "rust":
            commands.append("cargo build --release --manifest-path control-plane-rust/Cargo.toml 2>/dev/null || true")
            control_context = Path("control-plane-rust")
            control_dockerfile = Path("control-plane-rust/Dockerfile")
        else:
            control_context = Path("control-plane")
            control_dockerfile = Path("control-plane/Dockerfile")

        commands.append(
            _with_sudo(
                image_ops.build(
                    image=control_image,
                    context=control_context,
                    dockerfile=control_dockerfile,
                ).command,
                sudo=sudo,
            )
        )
        commands.append(
            _with_sudo(
                image_ops.build(
                    image=runtime_image,
                    context=Path("function-runtime"),
                ).command,
                sudo=sudo,
            )
        )

    commands.append(_with_sudo(image_ops.push(control_image).command, sudo=sudo))
    commands.append(_with_sudo(image_ops.push(runtime_image).command, sudo=sudo))
    return _render_remote_script(remote_dir=remote_dir, commands=commands)


def build_function_image_vm_script(
    *,
    remote_dir: str,
    image: str,
    runtime_kind: str,
    family: str,
    sudo: bool = True,
) -> str:
    image_ops = ImageOps(Path(remote_dir))
    if runtime_kind == "java":
        return _render_remote_script(
            remote_dir=remote_dir,
            commands=[
                [
                    "./gradlew",
                    f":examples:java:{family}:bootBuildImage",
                    f"-PfunctionImage={image}",
                    "--no-daemon",
                    "-q",
                ]
            ],
        )

    dockerfile_map = {
        "exec": Path(f"examples/bash/{family}/Dockerfile"),
        "go": Path(f"examples/go/{family}/Dockerfile"),
        "java-lite": Path(f"examples/java/{family}-lite/Dockerfile"),
        "javascript": Path(f"examples/javascript/{family}/Dockerfile"),
        "python": Path(f"examples/python/{family}/Dockerfile"),
    }
    if runtime_kind not in dockerfile_map:
        raise RuntimeError(f"Unsupported function runtime: {runtime_kind!r}")

    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[
            _with_sudo(
                image_ops.build(
                    image=image,
                    context=Path(".."),
                    dockerfile=dockerfile_map[runtime_kind],
                ).command,
                sudo=sudo,
            )
        ],
    )


def push_image_vm_script(
    *,
    remote_dir: str,
    image: str,
    sudo: bool = True,
) -> str:
    image_ops = ImageOps(Path(remote_dir))
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_sudo(image_ops.push(image).command, sudo=sudo)],
    )


def build_function_images_vm_script(
    *,
    remote_dir: str,
    functions: list[tuple[str, str, str]],
    sudo: bool = True,
    push: bool = True,
) -> str:
    commands: list[list[str] | str] = []
    prefix = f"cd {shlex.quote(remote_dir)} && "
    for image, runtime_kind, family in functions:
        commands.append(
            build_function_image_vm_script(
                remote_dir=remote_dir,
                image=image,
                runtime_kind=runtime_kind,
                family=family,
                sudo=sudo,
            ).removeprefix(prefix)
        )
        if push:
            commands.append(
                push_image_vm_script(
                    remote_dir=remote_dir,
                    image=image,
                    sudo=sudo,
                ).removeprefix(prefix)
            )
    return _render_remote_script(remote_dir=remote_dir, commands=commands)


def helm_upgrade_install_vm_script(
    *,
    remote_dir: str,
    release: str,
    chart: str,
    namespace: str,
    values: dict[str, str],
    kubeconfig_path: str | None = None,
    timeout: str = "3m",
) -> str:
    command = HelmOps(Path(remote_dir)).upgrade_install(
        release=release,
        chart=Path(chart),
        namespace=namespace,
        values=values,
        wait=True,
        timeout=timeout,
    ).command
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )


def helm_uninstall_vm_script(
    *,
    remote_dir: str,
    release: str,
    namespace: str,
    kubeconfig_path: str | None = None,
) -> str:
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[
            _with_kubeconfig(
                ["helm", "uninstall", release, "-n", namespace],
                kubeconfig_path=kubeconfig_path,
            )
        ],
    )


def _namespace_release_name(namespace: str) -> str:
    return f"{namespace}-namespace"


def helm_namespace_install_vm_script(
    *,
    remote_dir: str,
    namespace: str,
    kubeconfig_path: str | None = None,
    timeout: str = "2m",
) -> str:
    command = [
        "helm",
        "upgrade",
        "--install",
        _namespace_release_name(namespace),
        "helm/nanofaas-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        timeout,
        "--set",
        f"namespace.name={namespace}",
    ]
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )


def helm_namespace_uninstall_vm_script(
    *,
    remote_dir: str,
    namespace: str,
    kubeconfig_path: str | None = None,
    timeout: str = "5m",
) -> str:
    command = [
        "helm",
        "uninstall",
        _namespace_release_name(namespace),
        "-n",
        "default",
        "--wait",
        "--timeout",
        timeout,
        "--ignore-not-found",
    ]
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )


def k8s_e2e_test_vm_script(
    *,
    remote_dir: str,
    kubeconfig_path: str,
    runtime_image: str,
    namespace: str,
    remote_manifest_path: str | None = None,
) -> str:
    manifest_property = ""
    if remote_manifest_path is not None:
        manifest_property = f"-Dnanofaas.e2e.scenarioManifest={shlex.quote(remote_manifest_path)} "
    command = (
        f"KUBECONFIG={shlex.quote(kubeconfig_path)} "
        f"FUNCTION_RUNTIME_IMAGE={shlex.quote(runtime_image)} "
        f"NANOFAAS_E2E_NAMESPACE={shlex.quote(namespace)} "
        f"./gradlew :control-plane-modules:k8s-deployment-provider:test "
        f"{manifest_property}-PrunE2e --tests "
        "it.unimib.datai.nanofaas.modules.k8s.e2e.K8sE2eTest --no-daemon"
    )
    return _render_remote_script(remote_dir=remote_dir, commands=[command])
