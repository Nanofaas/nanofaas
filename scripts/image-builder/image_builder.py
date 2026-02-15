import re
import shutil
import subprocess
import sys
from pathlib import Path

import questionary
from rich.console import Console
from rich.panel import Panel

console = Console()

GH_OWNER = "miciav"
GH_REPO = "nanofaas"
REGISTRY = "ghcr.io"


def get_project_root() -> Path:
    """Find project root by searching for build.gradle in parent directories."""
    current = Path(__file__).resolve().parent
    for parent in [current] + list(current.parents):
        if (parent / "build.gradle").exists():
            return parent
    console.print("[red]Error: Could not find project root (build.gradle not found).[/red]")
    sys.exit(1)


ROOT = get_project_root()


def run_command(cmd: str, capture_output: bool = True) -> str:
    result = subprocess.run(
        cmd,
        shell=True,
        text=True,
        capture_output=capture_output,
        cwd=ROOT,
    )
    if result.returncode != 0:
        if capture_output:
            console.print(f"[red]Command failed: {cmd}[/red]")
            if result.stdout:
                console.print("[dim]--- stdout ---[/dim]")
                console.print(f"[dim]{result.stdout.rstrip()}[/dim]")
            if result.stderr:
                console.print("[dim]--- stderr ---[/dim]")
                console.print(f"[dim]{result.stderr.rstrip()}[/dim]")
        sys.exit(1)
    return result.stdout.strip()


def try_command(cmd: str) -> tuple[bool, str, str]:
    result = subprocess.run(
        cmd,
        shell=True,
        text=True,
        capture_output=True,
        cwd=ROOT,
    )
    return result.returncode == 0, result.stdout.strip(), result.stderr.strip()


def prune_docker_build_caches() -> None:
    if not shutil.which("docker"):
        return
    console.print("[yellow]Pruning Docker caches to free disk space...[/yellow]")
    try_command("docker builder prune -f")
    ok, volumes, _ = try_command("docker volume ls -q | grep '^pack-cache-' || true")
    if not ok:
        return
    for volume in [v for v in volumes.splitlines() if v.strip()]:
        try_command(f"docker volume rm {volume}")


def run_with_disk_retry(cmd: str, retries: int = 1) -> str:
    ok, out, err = try_command(cmd)
    if ok:
        return out

    text = f"{out}\n{err}"
    if retries > 0 and "no space left on device" in text.lower():
        prune_docker_build_caches()
        ok_retry, out_retry, err_retry = try_command(cmd)
        if ok_retry:
            return out_retry
        console.print(f"[red]Command failed after retry: {cmd}[/red]")
        if out_retry:
            console.print(f"[dim]{out_retry}[/dim]")
        if err_retry:
            console.print(f"[dim]{err_retry}[/dim]")
        sys.exit(1)

    console.print(f"[red]Command failed: {cmd}[/red]")
    if out:
        console.print(f"[dim]{out}[/dim]")
    if err:
        console.print(f"[dim]{err}[/dim]")
    sys.exit(1)


def get_current_version(root: Path | None = None) -> str:
    project_root = root or ROOT
    gradle_file = project_root / "build.gradle"
    content = gradle_file.read_text(encoding="utf-8")
    match = re.search(r"version\s*=\s*'([^']+)'", content)
    if match:
        return match.group(1)
    console.print("[red]Error: Could not find version in build.gradle[/red]")
    sys.exit(1)


IMAGES = {
    "control-plane": {
        "type": "gradle",
        "task": ":control-plane:bootBuildImage",
        "image_param": "controlPlaneImage",
        "group": "Core",
    },
    "function-runtime": {
        "type": "gradle",
        "task": ":function-runtime:bootBuildImage",
        "image_param": "functionRuntimeImage",
        "group": "Core",
    },
    "java-word-stats": {
        "type": "gradle",
        "task": ":examples:java:word-stats:bootBuildImage",
        "image_param": "functionImage",
        "group": "Java Functions",
    },
    "java-json-transform": {
        "type": "gradle",
        "task": ":examples:java:json-transform:bootBuildImage",
        "image_param": "functionImage",
        "group": "Java Functions",
    },
    "java-lite-word-stats": {
        "type": "docker",
        "dockerfile": "examples/java/word-stats-lite/Dockerfile",
        "context": ".",
        "group": "Java Lite Functions",
    },
    "java-lite-json-transform": {
        "type": "docker",
        "dockerfile": "examples/java/json-transform-lite/Dockerfile",
        "context": ".",
        "group": "Java Lite Functions",
    },
    "python-word-stats": {
        "type": "docker",
        "dockerfile": "examples/python/word-stats/Dockerfile",
        "context": ".",
        "group": "Python Functions",
    },
    "python-json-transform": {
        "type": "docker",
        "dockerfile": "examples/python/json-transform/Dockerfile",
        "context": ".",
        "group": "Python Functions",
    },
    "watchdog": {
        "type": "docker",
        "dockerfile": "watchdog/Dockerfile",
        "context": ".",
        "group": "Runtime",
    },
    "bash-word-stats": {
        "type": "docker",
        "dockerfile": "examples/bash/word-stats/Dockerfile",
        "context": ".",
        "group": "Bash Functions",
    },
    "bash-json-transform": {
        "type": "docker",
        "dockerfile": "examples/bash/json-transform/Dockerfile",
        "context": ".",
        "group": "Bash Functions",
    },
}


def resolve_selected_images(selected: list[str]) -> list[str]:
    if "All" in selected:
        return sorted(IMAGES.keys())
    return [name for name in selected if name in IMAGES]


def build_image_reference(name: str, tag: str, arch: str, use_arch_suffix: bool) -> str:
    if arch == "multi" or not use_arch_suffix:
        suffix = ""
    else:
        suffix = f"-{arch}"
    return f"{REGISTRY}/{GH_OWNER}/{GH_REPO}/{name}:{tag}{suffix}"


def build_gradle_command(image_cfg: dict[str, str], full_image: str, arch: str) -> str:
    platform = "linux/arm64,linux/amd64" if arch == "multi" else f"linux/{arch}"
    cmd = (
        f"./gradlew {image_cfg['task']} -P{image_cfg['image_param']}={full_image} "
        f"-PimagePlatform={platform}"
    )
    if arch == "arm64":
        cmd += (
            " -PimageBuilder=dashaun/builder:tiny"
            " -PimageRunImage=paketobuildpacks/run-jammy-tiny:latest"
        )
    return cmd


def build_docker_command(image_cfg: dict[str, str], full_image: str, arch: str) -> str:
    dockerfile = image_cfg["dockerfile"]
    context = image_cfg.get("context", ".")
    if arch == "multi":
        return (
            f"docker buildx build --platform linux/arm64,linux/amd64 -t {full_image} "
            f"-f {dockerfile} {context}"
        )
    return (
        f"docker build --platform linux/{arch} -t {full_image} "
        f"-f {dockerfile} {context}"
    )


def build_images(selected_images: list[str], tag: str, arch: str, use_arch_suffix: bool) -> list[str]:
    built_images: list[str] = []
    for name in selected_images:
        image_cfg = IMAGES[name]
        full_image = build_image_reference(name, tag, arch, use_arch_suffix)
        console.print(Panel(f"[bold]{name}[/bold]\n{full_image}", title="Building"))

        if image_cfg["type"] == "gradle":
            cmd = build_gradle_command(image_cfg, full_image, arch)
        else:
            cmd = build_docker_command(image_cfg, full_image, arch)

        run_with_disk_retry(cmd)
        built_images.append(full_image)

    return built_images


def push_images(images: list[str]) -> None:
    for image in images:
        console.print(f"[blue]Pushing {image}[/blue]")
        run_command(f"docker push {image}")


def build_choices() -> list[object]:
    choices: list[object] = ["All"]
    groups: dict[str, list[str]] = {}
    for name, cfg in IMAGES.items():
        groups.setdefault(cfg["group"], []).append(name)

    for group in sorted(groups.keys()):
        choices.append(questionary.Separator(f"--- {group} ---"))
        choices.extend(sorted(groups[group]))
    return choices


def main() -> None:
    default_tag = f"v{get_current_version()}"

    selected = questionary.checkbox(
        "Quale immagine?",
        choices=build_choices(),
        validate=lambda values: True if values else "Seleziona almeno un'immagine.",
    ).ask()
    if not selected:
        console.print("[yellow]Nessuna immagine selezionata. Uscita.[/yellow]")
        return

    tag = questionary.text("Quale tag?", default=default_tag).ask()
    if not tag:
        console.print("[yellow]Tag non valido. Uscita.[/yellow]")
        return

    arch = questionary.select(
        "Quale architettura?",
        choices=["arm64", "amd64", "multi"],
        default="arm64",
    ).ask()
    if not arch:
        console.print("[yellow]Architettura non valida. Uscita.[/yellow]")
        return

    use_arch_suffix = False
    if arch != "multi":
        use_arch_suffix = bool(
            questionary.confirm(
                "Suffisso architettura nel tag?",
                default=True,
            ).ask()
        )

    selected_images = resolve_selected_images(selected)
    if not selected_images:
        console.print("[yellow]Nessuna immagine valida selezionata. Uscita.[/yellow]")
        return

    built_images = build_images(selected_images, tag, arch, use_arch_suffix)

    should_push = bool(questionary.confirm("Push?", default=False).ask())
    if should_push:
        push_images(built_images)
        console.print("[green]Build e push completati.[/green]")
        return

    console.print("[green]Build completato.[/green]")


if __name__ == "__main__":
    main()
