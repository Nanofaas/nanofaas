from __future__ import annotations

import json
import os
import re
import stat
from pathlib import Path

TEMPLATES_DIR = Path(__file__).parent / "templates"

JAVA_FILE_MAP: dict[str, str] = {
    "Handler.java.tmpl": "src/main/java/{{PACKAGE_PATH}}/{{CLASS_NAME}}Handler.java",
    "Application.java.tmpl": "src/main/java/{{PACKAGE_PATH}}/{{CLASS_NAME}}Application.java",
    "HandlerTest.java.tmpl": "src/test/java/{{PACKAGE_PATH}}/{{CLASS_NAME}}HandlerTest.java",
    "build.gradle.tmpl": "build.gradle",
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

PYTHON_FILE_MAP: dict[str, str] = {
    "handler.py.tmpl": "handler.py",
    "handler_test.py.tmpl": "tests/test_handler.py",
    "pyproject.toml.tmpl": "pyproject.toml",
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

GO_FILE_MAP: dict[str, str] = {
    "main.go.tmpl": "main.go",
    "main_test.go.tmpl": "main_test.go",
    "go.mod.tmpl": "go.mod",
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

JAVASCRIPT_FILE_MAP: dict[str, str] = {
    "package.json.tmpl": "package.json",
    "tsconfig.json.tmpl": "tsconfig.json",
    "src/index.ts.tmpl": "src/index.ts",
    "src/handler.ts.tmpl": "src/handler.ts",
    "test/handler.test.ts.tmpl": "test/handler.test.ts",
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

BASH_FILE_MAP: dict[str, str] = {
    "handler.sh.tmpl": "handler.sh",
    "handler_test.sh.tmpl": "tests/test_handler.sh",
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

VSCODE_FILE_MAP: dict[str, str] = {
    "settings.json.tmpl": ".vscode/settings.json",
    "launch.json.tmpl": ".vscode/launch.json",
    "extensions.json.tmpl": ".vscode/extensions.json",
}

FILE_MAPS: dict[str, dict[str, str]] = {
    "java": JAVA_FILE_MAP,
    "python": PYTHON_FILE_MAP,
    "go": GO_FILE_MAP,
    "javascript": JAVASCRIPT_FILE_MAP,
    "bash": BASH_FILE_MAP,
}


def to_class_name(name: str) -> str:
    return "".join(word.capitalize() for word in re.split(r"[-_]", name))


def to_package(name: str) -> str:
    clean = re.sub(r"[-_]", "", name)
    return f"it.unimib.datai.nanofaas.examples.{clean}"


def render(text: str, placeholders: dict[str, str]) -> str:
    for key, value in placeholders.items():
        text = text.replace(f"{{{{{key}}}}}", value)
    return text


def detect_monorepo_root(start: Path) -> Path | None:
    current = start.resolve()
    while current != current.parent:
        if (current / "settings.gradle").exists():
            return current
        current = current.parent
    return None


def resolve_output_dir(
    name: str, lang: str, out: Path | None, cwd: Path
) -> tuple[Path, Path | None]:
    monorepo_root = detect_monorepo_root(cwd)
    if out is not None:
        return out / name, monorepo_root
    if monorepo_root is not None:
        return monorepo_root / "examples" / lang / name, monorepo_root
    raise ValueError("Not inside the nanofaas monorepo. Use --out to specify an output directory.")


def resolve_sdk_dependency_path(monorepo_root: Path | None, output_dir: Path) -> str:
    default_relative_path = "../../../function-sdk-javascript"
    if monorepo_root is None:
        return default_relative_path
    try:
        output_dir.resolve().relative_to(monorepo_root.resolve())
    except ValueError:
        return str((monorepo_root / "function-sdk-javascript").resolve())
    return os.path.relpath(monorepo_root / "function-sdk-javascript", output_dir)


def generate_function(
    name: str,
    lang: str,
    output_dir: Path,
    vscode: bool,
    placeholders: dict[str, str],
) -> list[Path]:
    if output_dir.exists():
        raise FileExistsError(f"Directory already exists: {output_dir}")

    created: list[Path] = []
    templates_dir = TEMPLATES_DIR / lang

    for tmpl_name, dest_pattern in FILE_MAPS[lang].items():
        tmpl_path = templates_dir / tmpl_name
        dest = output_dir / render(dest_pattern, placeholders)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(render(tmpl_path.read_text(), placeholders))
        if dest.suffix == ".sh":
            dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        created.append(dest)

    payloads_dir = output_dir / "payloads"
    (payloads_dir / "assets").mkdir(parents=True, exist_ok=True)
    happy = {
        "description": f"invoke {name} with valid input",
        "input": {"key": "value"},
        "expected": {"result": "ok"},
    }
    missing = {
        "description": f"invoke {name} with empty input",
        "input": {},
        "expected": {"result": "ok"},
    }
    (payloads_dir / "happy-path.json").write_text(json.dumps(happy, indent=2))
    (payloads_dir / "missing-input.json").write_text(json.dumps(missing, indent=2))
    created += [payloads_dir / "happy-path.json", payloads_dir / "missing-input.json"]

    if vscode:
        vscode_dir = TEMPLATES_DIR / "vscode" / lang
        for tmpl_name, dest_pattern in VSCODE_FILE_MAP.items():
            tmpl_path = vscode_dir / tmpl_name
            dest = output_dir / render(dest_pattern, placeholders)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(render(tmpl_path.read_text(), placeholders))
            created.append(dest)

    return created


def update_settings_gradle(monorepo_root: Path, name: str, lang: str) -> bool:
    if lang != "java":
        return False
    settings = monorepo_root / "settings.gradle"
    include_line = f"include 'examples:java:{name}'"
    content = settings.read_text()
    if include_line in content:
        return False
    settings.write_text(content.rstrip("\n") + f"\n{include_line}\n")
    return True
