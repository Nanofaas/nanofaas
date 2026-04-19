from __future__ import annotations

import re
import sys
from pathlib import Path

from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.prompt import Confirm, Prompt
from rich.tree import Tree

console = Console(force_terminal=sys.stdout.isatty())


def _valid_name(name: str) -> bool:
    return bool(re.match(r"^[a-z][a-z0-9-]*$", name))


def show_welcome() -> None:
    console.print(Panel(
        "[bold]nanofaas function scaffolding tool[/]\n\n"
        "Creates a new function project with handler, tests,\n"
        "build config, and payloads.",
        title="[bold blue]fn-init[/]",
        border_style="blue",
    ))


def ask_name() -> str:
    while True:
        name = Prompt.ask("[bold]Function name[/] [dim](lowercase, alphanumeric + hyphens)[/]")
        if _valid_name(name):
            return name
        console.print(f"[red]Invalid:[/] {escape(name)!r} — use lowercase letters, digits, and hyphens only")


def ask_lang() -> str:
    return Prompt.ask("[bold]Language[/]", choices=["java", "python"], default="java")


def ask_out(default: str | None) -> Path | None:
    if default:
        raw = Prompt.ask("[bold]Output directory[/]", default=default)
    else:
        raw = Prompt.ask("[bold]Output directory[/] [dim](required — not in monorepo)[/]")
    return Path(raw) if raw else None


def ask_vscode() -> bool:
    return Confirm.ask("[bold]Generate VS Code project files (.vscode/)?[/]", default=False)


def show_summary(output_dir: Path, lang: str, vscode: bool) -> None:
    tree = Tree(f"[bold]{output_dir.name}/[/]")
    if lang == "java":
        src = tree.add("[dim]src/[/]")
        main = src.add("[dim]main/java/.../[/]")
        main.add("Handler.java")
        main.add("Application.java")
        test = src.add("[dim]test/java/.../[/]")
        test.add("HandlerTest.java")
        tree.add("build.gradle")
        tree.add("Dockerfile")
    else:
        tree.add("handler.py")
        tree.add("[dim]tests/[/]test_handler.py")
        tree.add("Dockerfile")
    tree.add("function.yaml")
    payloads = tree.add("[dim]payloads/[/]")
    payloads.add("happy-path.json")
    payloads.add("missing-input.json")
    if vscode:
        vs = tree.add("[dim].vscode/[/]")
        vs.add("settings.json")
        vs.add("launch.json")
        vs.add("extensions.json")
    console.print(Panel(tree, title="[bold]Files to be created[/]", border_style="cyan"))


def confirm_proceed() -> bool:
    return Confirm.ask("[bold green]Proceed?[/]", default=True)


def show_next_steps(name: str, lang: str, output_dir: Path) -> None:
    unit_cmd = (
        f"./gradlew :examples:java:{name}:test"
        if lang == "java"
        else "uv run pytest"
    )
    console.print(Panel(
        f"[dim]cd[/] {output_dir}\n\n"
        "[dim]# implement your handler, then:[/]\n"
        "nanofaas deploy -f function.yaml\n"
        f"nanofaas invoke {name} -d @payloads/happy-path.json\n\n"
        "[dim]# run contract tests:[/]\n"
        f"nanofaas fn test {name} --payloads ./payloads/\n\n"
        "[dim]# run unit tests:[/]\n"
        f"{unit_cmd}",
        title="[bold green]Next steps[/]",
        border_style="green",
    ))
