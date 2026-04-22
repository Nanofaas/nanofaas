from __future__ import annotations
import json
import re
import sys
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.markup import escape

from fn_init import generator, wizard

app = typer.Typer(add_completion=False, help="Scaffold a new nanofaas function project.")
console = Console(force_terminal=sys.stdout.isatty())
DEFAULT_JAVASCRIPT_SDK_VERSION = "0.16.1"


@app.command()
def main(
    name: Optional[str] = typer.Argument(None, help="Function name (lowercase, alphanumeric + hyphens)"),
    lang: str = typer.Option("java", "--lang", help="Language: java, python, go, javascript, or bash"),
    out: Optional[Path] = typer.Option(None, "--out", help="Parent output directory"),
    vscode: bool = typer.Option(False, "--vscode", help="Generate VS Code project files"),
    yes: bool = typer.Option(False, "--yes", "-y", help="Skip confirmation prompts"),
) -> None:
    cwd = Path.cwd()

    if name is None:
        wizard.show_welcome()
        monorepo_root = generator.detect_monorepo_root(cwd)
        name = wizard.ask_name()
        lang = wizard.ask_lang()
        default_out = str(monorepo_root / "examples" / lang / name) if monorepo_root else None
        out = wizard.ask_out(default_out)
        vscode = wizard.ask_vscode()

    if not re.match(r"^[a-z][a-z0-9-]*$", name):
        console.print(f"[red]Error:[/] invalid function name {escape(name)!r} — use lowercase letters, digits, and hyphens only")
        raise typer.Exit(1)

    if lang not in ("java", "python", "go", "javascript", "bash"):
        console.print(f"[red]Error:[/] unsupported language {escape(lang)!r}. Choose java, python, go, javascript, or bash.")
        raise typer.Exit(1)

    class_name = generator.to_class_name(name)
    package = generator.to_package(name)

    try:
        output_dir, monorepo_root = generator.resolve_output_dir(name, lang, out, cwd)
    except ValueError as e:
        console.print(f"[red]Error:[/] {e}")
        raise typer.Exit(1)

    sdk_package = (
        json.loads((monorepo_root / "function-sdk-javascript" / "package.json").read_text(encoding="utf-8"))
        if monorepo_root is not None
        else {"version": DEFAULT_JAVASCRIPT_SDK_VERSION}
    )

    javascript_contract = (
        generator.build_javascript_scaffold_contract(monorepo_root, output_dir, sdk_package["version"])
        if lang == "javascript"
        else {}
    )

    placeholders = {
        "FUNCTION_NAME": name,
        "CLASS_NAME": class_name,
        "PACKAGE": package,
        "PACKAGE_PATH": package.replace(".", "/"),
        "IMAGE_TAG": f"nanofaas/{name}:latest",
        "LANG": lang,
        "SDK_DEPENDENCY": "",
        "SDK_BUILD_HOOKS": "",
        "BUILD_CONTEXT": ".",
        "DOCKERFILE_PATH": "Dockerfile",
        "DOCKER_APP_COPY": "",
        "DOCKER_APP_DIR": "/src/app",
        "DOCKER_SDK_COPY": "",
        "DOCKER_SDK_BUILD_BLOCK": "",
        "DOCKER_FINAL_SDK_COPY": "",
    }
    placeholders.update(javascript_contract)

    if output_dir.exists():
        console.print(f"[red]Error:[/] directory already exists: {output_dir}")
        raise typer.Exit(1)

    if not yes:
        wizard.show_summary(output_dir, lang, vscode)
        if not wizard.confirm_proceed():
            console.print("[yellow]Aborted.[/]")
            raise typer.Exit(0)

    with console.status("[bold green]Generating..."):
        generator.generate_function(name, lang, output_dir, vscode, placeholders)
        if lang == "javascript" and monorepo_root and generator.should_vendor_javascript_sdk(monorepo_root, output_dir):
            generator.vendor_javascript_sdk(monorepo_root, output_dir)
        if monorepo_root and lang == "java":  # only Java has centralised Gradle registry
            if generator.update_settings_gradle(monorepo_root, name, lang):
                console.print(f"[dim]Updated settings.gradle → added include 'examples:java:{name}'[/]")

    wizard.show_next_steps(name, lang, output_dir)


def entry() -> None:
    app()
