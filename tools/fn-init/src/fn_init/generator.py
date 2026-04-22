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
        return (out if out.name == name else out / name), monorepo_root
    if monorepo_root is not None:
        return monorepo_root / "examples" / lang / name, monorepo_root
    raise ValueError("Not inside the nanofaas monorepo. Use --out to specify an output directory.")


def resolve_sdk_dependency_spec(monorepo_root: Path | None, output_dir: Path, published_version: str) -> str:
    if monorepo_root is None:
        return published_version
    try:
        output_dir.resolve().relative_to(monorepo_root.resolve())
    except ValueError:
        return published_version
    relative = os.path.relpath(monorepo_root / "function-sdk-javascript", output_dir)
    return f"file:{relative}"


def render_sdk_build_hooks(monorepo_root: Path | None, output_dir: Path) -> str:
    if monorepo_root is None:
        return ""
    try:
        output_dir.resolve().relative_to(monorepo_root.resolve())
    except ValueError:
        return ""
    relative = os.path.relpath(monorepo_root / "function-sdk-javascript", output_dir)
    return (
        f'    "prebuild": "npm --prefix {relative} install && npm --prefix {relative} run build",\n'
        f'    "pretest": "npm --prefix {relative} install && npm --prefix {relative} run build",\n'
    )


def build_javascript_scaffold_contract(
    monorepo_root: Path | None,
    output_dir: Path,
    published_version: str,
) -> dict[str, str]:
    sdk_dependency = resolve_sdk_dependency_spec(monorepo_root, output_dir, published_version)
    sdk_build_hooks = render_sdk_build_hooks(monorepo_root, output_dir)

    repo_relative_output: str | None = None
    if monorepo_root is not None:
        try:
            repo_relative_output = os.path.relpath(output_dir.resolve(), monorepo_root.resolve())
        except ValueError:
            repo_relative_output = None

    if repo_relative_output is None or repo_relative_output.startswith(".."):
        return {
            "SDK_DEPENDENCY": sdk_dependency,
            "SDK_BUILD_HOOKS": sdk_build_hooks,
            "BUILD_CONTEXT": ".",
            "DOCKERFILE_PATH": "Dockerfile",
            "DOCKER_APP_COPY": "COPY . /src/app",
            "DOCKER_APP_DIR": "/src/app",
            "DOCKER_SDK_COPY": "",
            "DOCKER_FINAL_SDK_COPY": "",
        }

    normalized_output = repo_relative_output.replace(os.sep, "/")
    build_context = os.path.relpath(monorepo_root.resolve(), output_dir.resolve()).replace(os.sep, "/")
    docker_app_dir = f"/src/{normalized_output}"

    return {
        "SDK_DEPENDENCY": sdk_dependency,
        "SDK_BUILD_HOOKS": sdk_build_hooks,
        "BUILD_CONTEXT": build_context,
        "DOCKERFILE_PATH": f"{normalized_output}/Dockerfile",
        "DOCKER_APP_COPY": f"COPY {normalized_output} {docker_app_dir}",
        "DOCKER_APP_DIR": docker_app_dir,
        "DOCKER_SDK_COPY": "COPY function-sdk-javascript ./function-sdk-javascript",
        "DOCKER_FINAL_SDK_COPY": "COPY --from=build /src/function-sdk-javascript /function-sdk-javascript",
    }


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
