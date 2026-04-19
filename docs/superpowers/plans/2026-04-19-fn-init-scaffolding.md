# fn-init: Function Scaffolding Tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `tools/fn-init` — a Rich-powered interactive wizard that scaffolds a nanofaas function project (handler, tests, build config, payloads, optional VS Code files) for Java and Python.

**Architecture:** A uv Python project under `tools/fn-init/` following the same pattern as `tools/controlplane/`. Core is split into `generator.py` (pure logic, fully tested), `wizard.py` (Rich UI), and `main.py` (Typer CLI entrypoint). Templates live in `src/fn_init/templates/`. A shell wrapper `scripts/fn-init.sh` handles `uv sync + run` transparently.

**Tech Stack:** Python 3.11+, `rich>=13.8`, `typer>=0.12.5`, `pytest` (dev), uv, `{{PLACEHOLDER}}` template substitution via `str.replace()`

**Spec:** `docs/superpowers/specs/2026-04-19-fn-init-scaffolding-design.md`

---

## File Map

**Create:**
- `tools/fn-init/pyproject.toml`
- `tools/fn-init/src/fn_init/__init__.py`
- `tools/fn-init/src/fn_init/generator.py`
- `tools/fn-init/src/fn_init/wizard.py`
- `tools/fn-init/src/fn_init/main.py`
- `tools/fn-init/src/fn_init/templates/java/Handler.java.tmpl`
- `tools/fn-init/src/fn_init/templates/java/Application.java.tmpl`
- `tools/fn-init/src/fn_init/templates/java/HandlerTest.java.tmpl`
- `tools/fn-init/src/fn_init/templates/java/build.gradle.tmpl`
- `tools/fn-init/src/fn_init/templates/java/Dockerfile.tmpl`
- `tools/fn-init/src/fn_init/templates/java/function.yaml.tmpl`
- `tools/fn-init/src/fn_init/templates/python/handler.py.tmpl`
- `tools/fn-init/src/fn_init/templates/python/handler_test.py.tmpl`
- `tools/fn-init/src/fn_init/templates/python/Dockerfile.tmpl`
- `tools/fn-init/src/fn_init/templates/python/function.yaml.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/java/settings.json.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/java/launch.json.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/java/extensions.json.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/python/settings.json.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/python/launch.json.tmpl`
- `tools/fn-init/src/fn_init/templates/vscode/python/extensions.json.tmpl`
- `tools/fn-init/tests/__init__.py`
- `tools/fn-init/tests/test_generator.py`
- `scripts/fn-init.sh`

**Modify:**
- `docs/tutorial-java-function.md` → replaced by `docs/tutorial-function.md`

---

## Task 1: Bootstrap uv project

**Files:**
- Create: `tools/fn-init/pyproject.toml`
- Create: `tools/fn-init/src/fn_init/__init__.py`
- Create: `tools/fn-init/tests/__init__.py`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/fn-init/src/fn_init/templates
mkdir -p tools/fn-init/tests
touch tools/fn-init/src/fn_init/__init__.py
touch tools/fn-init/tests/__init__.py
```

- [ ] **Step 2: Write `tools/fn-init/pyproject.toml`**

```toml
[project]
name = "fn-init"
version = "0.1.0"
description = "Function scaffolding tool for nanofaas"
requires-python = ">=3.11"
dependencies = [
    "rich>=13.8",
    "typer>=0.12.5",
]

[project.scripts]
fn-init = "fn_init.main:main"

[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
package-dir = {"" = "src"}

[tool.setuptools.packages.find]
where = ["src"]

[dependency-groups]
dev = [
    "pytest>=8.3.4",
]
```

- [ ] **Step 3: Install dependencies and verify**

```bash
cd tools/fn-init
uv sync
uv run pytest --collect-only
```

Expected: `no tests ran` (empty test suite, no errors)

- [ ] **Step 4: Commit**

```bash
git add tools/fn-init/
git commit -m "feat(fn-init): bootstrap uv project"
```

---

## Task 2: Write generator tests (TDD — all failing)

**Files:**
- Create: `tools/fn-init/tests/test_generator.py`

- [ ] **Step 1: Write all generator tests**

```python
# tools/fn-init/tests/test_generator.py
import json
import pytest
from pathlib import Path
from fn_init.generator import (
    to_class_name,
    to_package,
    render,
    detect_monorepo_root,
    resolve_output_dir,
    generate_function,
    update_settings_gradle,
)


# --- to_class_name ---

def test_to_class_name_simple():
    assert to_class_name("greet") == "Greet"

def test_to_class_name_kebab():
    assert to_class_name("word-stats") == "WordStats"

def test_to_class_name_underscore():
    assert to_class_name("json_transform") == "JsonTransform"

def test_to_class_name_multi_segment():
    assert to_class_name("my-cool-fn") == "MyCoolFn"


# --- to_package ---

def test_to_package_simple():
    assert to_package("greet") == "it.unimib.datai.nanofaas.examples.greet"

def test_to_package_strips_hyphens():
    assert to_package("word-stats") == "it.unimib.datai.nanofaas.examples.wordstats"

def test_to_package_strips_underscores():
    assert to_package("json_transform") == "it.unimib.datai.nanofaas.examples.jsontransform"


# --- render ---

def test_render_replaces_placeholder():
    assert render("Hello, {{NAME}}!", {"NAME": "world"}) == "Hello, world!"

def test_render_multiple_placeholders():
    result = render("{{A}} and {{B}}", {"A": "foo", "B": "bar"})
    assert result == "foo and bar"

def test_render_no_match_unchanged():
    assert render("no placeholders", {"X": "y"}) == "no placeholders"


# --- detect_monorepo_root ---

def test_detect_monorepo_root_finds_settings(tmp_path):
    (tmp_path / "settings.gradle").write_text("// settings")
    subdir = tmp_path / "examples" / "java" / "greet"
    subdir.mkdir(parents=True)
    assert detect_monorepo_root(subdir) == tmp_path

def test_detect_monorepo_root_from_root(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    assert detect_monorepo_root(tmp_path) == tmp_path

def test_detect_monorepo_root_returns_none(tmp_path):
    assert detect_monorepo_root(tmp_path) is None


# --- resolve_output_dir ---

def test_resolve_in_monorepo_no_out(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    out, root = resolve_output_dir("greet", "java", None, tmp_path)
    assert out == tmp_path / "examples" / "java" / "greet"
    assert root == tmp_path

def test_resolve_in_monorepo_with_out(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    custom = tmp_path / "custom"
    out, root = resolve_output_dir("greet", "java", custom, tmp_path)
    assert out == custom / "greet"
    assert root == tmp_path

def test_resolve_outside_monorepo_with_out(tmp_path):
    custom = tmp_path / "projects"
    out, root = resolve_output_dir("greet", "java", custom, tmp_path)
    assert out == custom / "greet"
    assert root is None

def test_resolve_outside_monorepo_no_out_raises(tmp_path):
    with pytest.raises(ValueError, match="--out"):
        resolve_output_dir("greet", "java", None, tmp_path)


# --- generate_function (Java) ---

JAVA_PLACEHOLDERS = {
    "FUNCTION_NAME": "greet",
    "CLASS_NAME": "Greet",
    "PACKAGE": "it.unimib.datai.nanofaas.examples.greet",
    "PACKAGE_PATH": "it/unimib/datai/nanofaas/examples/greet",
    "IMAGE_TAG": "nanofaas/greet:latest",
    "LANG": "java",
}

def test_generate_java_creates_handler(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    handler = out / "src/main/java/it/unimib/datai/nanofaas/examples/greet/GreetHandler.java"
    assert handler.exists()
    assert "GreetHandler" in handler.read_text()

def test_generate_java_creates_application(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    app = out / "src/main/java/it/unimib/datai/nanofaas/examples/greet/GreetApplication.java"
    assert app.exists()

def test_generate_java_creates_test(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    test = out / "src/test/java/it/unimib/datai/nanofaas/examples/greet/GreetHandlerTest.java"
    assert test.exists()

def test_generate_java_creates_build_files(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    assert (out / "build.gradle").exists()
    assert (out / "Dockerfile").exists()
    assert (out / "function.yaml").exists()

def test_generate_java_creates_payloads(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    assert (out / "payloads" / "happy-path.json").exists()
    assert (out / "payloads" / "missing-input.json").exists()
    assert (out / "payloads" / "assets").is_dir()

def test_generate_java_payload_format(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=False, placeholders=JAVA_PLACEHOLDERS)
    payload = json.loads((out / "payloads" / "happy-path.json").read_text())
    assert "description" in payload
    assert "input" in payload
    assert "expected" in payload

def test_generate_java_vscode(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "java", out, vscode=True, placeholders=JAVA_PLACEHOLDERS)
    assert (out / ".vscode" / "settings.json").exists()
    assert (out / ".vscode" / "launch.json").exists()
    assert (out / ".vscode" / "extensions.json").exists()

def test_generate_existing_dir_raises(tmp_path):
    out = tmp_path / "greet"
    out.mkdir()
    with pytest.raises(FileExistsError):
        generate_function("greet", "java", out, False, JAVA_PLACEHOLDERS)


# --- generate_function (Python) ---

PYTHON_PLACEHOLDERS = {
    "FUNCTION_NAME": "greet",
    "CLASS_NAME": "Greet",
    "PACKAGE": "it.unimib.datai.nanofaas.examples.greet",
    "PACKAGE_PATH": "it/unimib/datai/nanofaas/examples/greet",
    "IMAGE_TAG": "nanofaas/greet:latest",
    "LANG": "python",
}

def test_generate_python_creates_handler(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "python", out, vscode=False, placeholders=PYTHON_PLACEHOLDERS)
    assert (out / "handler.py").exists()
    assert "nanofaas_function" in (out / "handler.py").read_text()

def test_generate_python_creates_build_files(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "python", out, vscode=False, placeholders=PYTHON_PLACEHOLDERS)
    assert (out / "Dockerfile").exists()
    assert (out / "function.yaml").exists()

def test_generate_python_creates_payloads(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "python", out, vscode=False, placeholders=PYTHON_PLACEHOLDERS)
    assert (out / "payloads" / "happy-path.json").exists()


# --- update_settings_gradle ---

def test_update_settings_gradle_appends(tmp_path):
    (tmp_path / "settings.gradle").write_text("include 'common'\n")
    modified = update_settings_gradle(tmp_path, "greet", "java")
    assert modified is True
    assert "include 'examples:java:greet'" in (tmp_path / "settings.gradle").read_text()

def test_update_settings_gradle_idempotent(tmp_path):
    (tmp_path / "settings.gradle").write_text("include 'examples:java:greet'\n")
    assert update_settings_gradle(tmp_path, "greet", "java") is False

def test_update_settings_gradle_python_noop(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    assert update_settings_gradle(tmp_path, "my-fn", "python") is False
    assert (tmp_path / "settings.gradle").read_text() == ""
```

- [ ] **Step 2: Run tests to verify they all fail**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v 2>&1 | head -30
```

Expected: `ImportError: cannot import name ... from 'fn_init.generator'` (module doesn't exist yet)

- [ ] **Step 3: Commit failing tests**

```bash
git add tools/fn-init/tests/test_generator.py
git commit -m "test(fn-init): add generator tests (TDD — all failing)"
```

---

## Task 3: Implement generator.py

**Files:**
- Create: `tools/fn-init/src/fn_init/generator.py`

- [ ] **Step 1: Write `generator.py`**

```python
# tools/fn-init/src/fn_init/generator.py
from __future__ import annotations

import json
import re
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
    "Dockerfile.tmpl": "Dockerfile",
    "function.yaml.tmpl": "function.yaml",
}

VSCODE_FILE_MAP: dict[str, str] = {
    "settings.json.tmpl": ".vscode/settings.json",
    "launch.json.tmpl": ".vscode/launch.json",
    "extensions.json.tmpl": ".vscode/extensions.json",
}

FILE_MAPS: dict[str, dict[str, str]] = {"java": JAVA_FILE_MAP, "python": PYTHON_FILE_MAP}


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
        created.append(dest)

    # payloads/
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
```

- [ ] **Step 2: Run tests — expect failures only for missing templates**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v 2>&1 | grep -E "PASSED|FAILED|ERROR"
```

Expected: pure-logic tests pass (`to_class_name`, `to_package`, `render`, `detect_monorepo_root`, `resolve_output_dir`, `update_settings_gradle`). `generate_function` tests fail with `FileNotFoundError` (templates missing).

- [ ] **Step 3: Commit**

```bash
git add tools/fn-init/src/fn_init/generator.py
git commit -m "feat(fn-init): implement generator core"
```

---

## Task 4: Java templates

**Files:**
- Create: `tools/fn-init/src/fn_init/templates/java/Handler.java.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/java/Application.java.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/java/HandlerTest.java.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/java/build.gradle.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/java/Dockerfile.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/java/function.yaml.tmpl`

- [ ] **Step 1: Write `Handler.java.tmpl`**

```java
package {{PACKAGE}};

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.FunctionContext;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.slf4j.Logger;

import java.util.Map;

@NanofaasFunction
public class {{CLASS_NAME}}Handler implements FunctionHandler {

    private static final Logger log = FunctionContext.getLogger({{CLASS_NAME}}Handler.class);

    @Override
    public Object handle(InvocationRequest request) {
        log.info("{{FUNCTION_NAME}} invoked, executionId={}", FunctionContext.getExecutionId());
        // TODO: implement handler logic
        return Map.of("result", "ok");
    }
}
```

- [ ] **Step 2: Write `Application.java.tmpl`**

```java
package {{PACKAGE}};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class {{CLASS_NAME}}Application {
    public static void main(String[] args) {
        SpringApplication.run({{CLASS_NAME}}Application.class, args);
    }
}
```

- [ ] **Step 3: Write `HandlerTest.java.tmpl`**

```java
package {{PACKAGE}};

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class {{CLASS_NAME}}HandlerTest {

    private final {{CLASS_NAME}}Handler handler = new {{CLASS_NAME}}Handler();

    @Test
    void handleReturnsResult() {
        var req = new InvocationRequest(Map.of(), null);
        var result = handler.handle(req);
        assertNotNull(result);
    }
}
```

- [ ] **Step 4: Write `build.gradle.tmpl`**

```groovy
plugins {
    id 'org.springframework.boot' version "${springBootVersion}"
    id 'io.spring.dependency-management' version "${springDependencyManagementVersion}"
    id 'java'
}

dependencies {
    implementation project(':function-sdk-java')
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}

bootJar {
    archiveFileName = '{{FUNCTION_NAME}}.jar'
}
```

- [ ] **Step 5: Write `Dockerfile.tmpl`**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/{{FUNCTION_NAME}}.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 6: Write `function.yaml.tmpl`**

```yaml
name: {{FUNCTION_NAME}}
image: {{IMAGE_TAG}}
timeoutMs: 10000
concurrency: 2
executionMode: DEPLOYMENT

x-cli:
  build:
    context: .
    dockerfile: Dockerfile
    platform: linux/amd64
    push: true
```

- [ ] **Step 7: Run Java generator tests**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v -k "java"
```

Expected: all Java `generate_function` tests pass.

- [ ] **Step 8: Commit**

```bash
git add tools/fn-init/src/fn_init/templates/java/
git commit -m "feat(fn-init): add Java function templates"
```

---

## Task 5: Python templates

**Files:**
- Create: `tools/fn-init/src/fn_init/templates/python/handler.py.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/python/handler_test.py.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/python/Dockerfile.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/python/function.yaml.tmpl`

- [ ] **Step 1: Write `handler.py.tmpl`**

```python
from nanofaas.sdk import nanofaas_function, context

logger = context.get_logger(__name__)


@nanofaas_function
def handle(input_data):
    logger.info(f"{{FUNCTION_NAME}} invoked, executionId={context.get_execution_id()}")
    # TODO: implement handler logic
    return {"result": "ok"}
```

- [ ] **Step 2: Write `handler_test.py.tmpl`**

```python
from unittest.mock import patch
from handler import handle


def test_handle_returns_result():
    with patch("nanofaas.sdk.context.get_execution_id", return_value="test-id"):
        result = handle({"key": "value"})
    assert result == {"result": "ok"}


def test_handle_empty_input():
    with patch("nanofaas.sdk.context.get_execution_id", return_value="test-id"):
        result = handle({})
    assert "result" in result
```

- [ ] **Step 3: Write `Dockerfile.tmpl`**

```dockerfile
# Stage 1: install SDK
FROM python:3.11-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/
COPY function-sdk-python/ /tmp/sdk/
RUN /uv/bin/uv pip install --system --target /deps /tmp/sdk/

# Stage 2: minimal runtime
FROM python:3.11-slim
LABEL org.opencontainers.image.source=https://github.com/miciav/nanofaas
WORKDIR /app
COPY --from=builder /deps /usr/local/lib/python3.11/site-packages/
COPY . /app/
ENV HANDLER_MODULE=handler PORT=8080 PYTHONPATH=/app
EXPOSE 8080
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
```

- [ ] **Step 4: Write `function.yaml.tmpl`**

```yaml
name: {{FUNCTION_NAME}}
image: {{IMAGE_TAG}}
timeoutMs: 10000
concurrency: 2
executionMode: DEPLOYMENT

x-cli:
  build:
    context: ../../../..
    dockerfile: examples/python/{{FUNCTION_NAME}}/Dockerfile
    platform: linux/amd64
    push: true
```

- [ ] **Step 5: Run Python generator tests**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v -k "python"
```

Expected: all Python `generate_function` tests pass.

- [ ] **Step 6: Run full test suite — all green**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add tools/fn-init/src/fn_init/templates/python/
git commit -m "feat(fn-init): add Python function templates"
```

---

## Task 6: VS Code templates

**Files:**
- Create: `tools/fn-init/src/fn_init/templates/vscode/java/*.json.tmpl`
- Create: `tools/fn-init/src/fn_init/templates/vscode/python/*.json.tmpl`

- [ ] **Step 1: Write Java VS Code templates**

`tools/fn-init/src/fn_init/templates/vscode/java/settings.json.tmpl`:
```json
{
    "java.project.sourcePaths": ["src/main/java", "src/test/java"],
    "java.project.outputPath": "build/classes",
    "editor.formatOnSave": true,
    "editor.defaultFormatter": "redhat.java"
}
```

`tools/fn-init/src/fn_init/templates/vscode/java/launch.json.tmpl`:
```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Run {{CLASS_NAME}}Application",
            "request": "launch",
            "mainClass": "{{PACKAGE}}.{{CLASS_NAME}}Application"
        }
    ]
}
```

`tools/fn-init/src/fn_init/templates/vscode/java/extensions.json.tmpl`:
```json
{
    "recommendations": [
        "vscjava.vscode-java-pack",
        "vmware.vscode-spring-boot",
        "vscjava.vscode-gradle"
    ]
}
```

- [ ] **Step 2: Write Python VS Code templates**

`tools/fn-init/src/fn_init/templates/vscode/python/settings.json.tmpl`:
```json
{
    "python.defaultInterpreterPath": ".venv/bin/python",
    "editor.formatOnSave": true,
    "[python]": {
        "editor.defaultFormatter": "charliermarsh.ruff"
    }
}
```

`tools/fn-init/src/fn_init/templates/vscode/python/launch.json.tmpl`:
```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "python",
            "name": "Run {{FUNCTION_NAME}} handler",
            "request": "launch",
            "module": "uvicorn",
            "args": ["nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
        }
    ]
}
```

`tools/fn-init/src/fn_init/templates/vscode/python/extensions.json.tmpl`:
```json
{
    "recommendations": [
        "ms-python.python",
        "charliermarsh.ruff"
    ]
}
```

- [ ] **Step 3: Run vscode tests**

```bash
cd tools/fn-init
uv run pytest tests/test_generator.py -v -k "vscode"
```

Expected: `test_generate_java_vscode` passes.

- [ ] **Step 4: Commit**

```bash
git add tools/fn-init/src/fn_init/templates/vscode/
git commit -m "feat(fn-init): add VS Code project templates"
```

---

## Task 7: Rich wizard UI

**Files:**
- Create: `tools/fn-init/src/fn_init/wizard.py`

- [ ] **Step 1: Write `wizard.py`**

```python
# tools/fn-init/src/fn_init/wizard.py
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
```

- [ ] **Step 2: Verify import**

```bash
cd tools/fn-init
uv run python -c "from fn_init.wizard import show_welcome; print('ok')"
```

Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add tools/fn-init/src/fn_init/wizard.py
git commit -m "feat(fn-init): add Rich wizard UI"
```

---

## Task 8: CLI entrypoint

**Files:**
- Create: `tools/fn-init/src/fn_init/main.py`

- [ ] **Step 1: Write `main.py`**

```python
# tools/fn-init/src/fn_init/main.py
from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.markup import escape

from fn_init import generator, wizard

app = typer.Typer(add_completion=False, help="Scaffold a new nanofaas function project.")
console = Console(force_terminal=sys.stdout.isatty())


@app.command()
def main(
    name: Optional[str] = typer.Argument(None, help="Function name (lowercase, alphanumeric + hyphens)"),
    lang: str = typer.Option("java", "--lang", help="Language: java or python"),
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

    if lang not in ("java", "python"):
        console.print(f"[red]Error:[/] unsupported language {escape(lang)!r}. Choose java or python.")
        raise typer.Exit(1)

    class_name = generator.to_class_name(name)
    package = generator.to_package(name)
    placeholders = {
        "FUNCTION_NAME": name,
        "CLASS_NAME": class_name,
        "PACKAGE": package,
        "PACKAGE_PATH": package.replace(".", "/"),
        "IMAGE_TAG": f"nanofaas/{name}:latest",
        "LANG": lang,
    }

    try:
        output_dir, monorepo_root = generator.resolve_output_dir(name, lang, out, cwd)
    except ValueError as e:
        console.print(f"[red]Error:[/] {e}")
        raise typer.Exit(1)

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
        if monorepo_root and lang == "java":
            if generator.update_settings_gradle(monorepo_root, name, lang):
                console.print(f"[dim]Updated settings.gradle → added include 'examples:java:{name}'[/]")

    wizard.show_next_steps(name, lang, output_dir)


def entry() -> None:
    app()
```

- [ ] **Step 2: Verify help works**

```bash
cd tools/fn-init
uv run fn-init --help
```

Expected output includes: `Usage: fn-init [OPTIONS] [NAME]` and the `--lang`, `--out`, `--vscode`, `--yes` flags.

- [ ] **Step 3: Commit**

```bash
git add tools/fn-init/src/fn_init/main.py
git commit -m "feat(fn-init): add Typer CLI entrypoint"
```

---

## Task 9: Shell wrapper and smoke test

**Files:**
- Create: `scripts/fn-init.sh`

- [ ] **Step 1: Write `scripts/fn-init.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install uv or provision the VM with ops/ansible/playbooks/provision-base.yml" >&2
  exit 1
fi

exec uv run --project tools/fn-init --locked fn-init "$@"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/fn-init.sh
```

- [ ] **Step 3: Lock dependencies**

```bash
cd tools/fn-init && uv lock
```

- [ ] **Step 4: Smoke test — Java scaffold**

```bash
./scripts/fn-init.sh greet --lang java --yes
```

Expected:
- Directory `examples/java/greet/` created
- `examples/java/greet/src/main/java/.../GreetHandler.java` exists
- `examples/java/greet/payloads/happy-path.json` exists
- `settings.gradle` contains `include 'examples:java:greet'`
- Green "Next steps" panel printed

Verify:
```bash
ls examples/java/greet/
cat examples/java/greet/payloads/happy-path.json
grep "examples:java:greet" settings.gradle
```

- [ ] **Step 5: Smoke test — Python scaffold**

```bash
./scripts/fn-init.sh my-fn --lang python --out /tmp/nanofaas-test --yes
```

Expected:
- `/tmp/nanofaas-test/my-fn/handler.py` exists and contains `@nanofaas_function`
- `/tmp/nanofaas-test/my-fn/payloads/happy-path.json` exists
- `settings.gradle` NOT modified

Verify:
```bash
grep "nanofaas_function" /tmp/nanofaas-test/my-fn/handler.py
ls /tmp/nanofaas-test/my-fn/payloads/
```

- [ ] **Step 6: Smoke test — existing directory error**

```bash
./scripts/fn-init.sh greet --lang java --yes 2>&1 | grep -i "already exists"
```

Expected: red error panel, exit code 1.

- [ ] **Step 7: Clean up smoke test artifacts**

```bash
rm -rf examples/java/greet /tmp/nanofaas-test
# Remove the settings.gradle line added during smoke test
sed -i '' "/include 'examples:java:greet'/d" settings.gradle
```

- [ ] **Step 8: Run full test suite one last time**

```bash
cd tools/fn-init && uv run pytest tests/ -v
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add scripts/fn-init.sh tools/fn-init/uv.lock
git commit -m "feat(fn-init): add shell wrapper and lock dependencies"
```

---

## Task 10: Unified tutorial

**Files:**
- Create: `docs/tutorial-function.md`
- Delete: `docs/tutorial-java-function.md`

- [ ] **Step 1: Write `docs/tutorial-function.md`**

```markdown
# Tutorial: Writing a nanofaas Function

This tutorial walks you through creating, building, and invoking a nanofaas
function from scratch. Examples are shown for Java and Python; sections that
differ between languages are marked with **Java** / **Python** tabs.

---

## Prerequisites

| Requirement | Version |
|---|---|
| nanofaas CLI (`nanofaas`) | any recent |
| Java (SDKMAN recommended) | 21 — *Java only* |
| Docker or compatible runtime | any recent |
| nanofaas platform running | — |

Start the platform locally:

```bash
scripts/controlplane.sh run --profile core   # API on http://localhost:8080
```

---

## Concepts

A nanofaas function is an HTTP service that implements one endpoint (`POST /invoke`).
The SDK wires up the server; you write only the handler.

The platform calls your handler with an `InvocationRequest`:

| Field | Type | Description |
|---|---|---|
| `input` | any | JSON body sent by the caller |
| `metadata` | map | Optional caller-supplied metadata |

Whatever your handler returns is serialized back to the caller as JSON.

---

## Step 1 — Scaffold the project

```bash
./scripts/fn-init.sh
```

The interactive wizard asks for a function name, language, and output directory,
then generates a ready-to-run project:

```
greet/
├── src/…/GreetHandler.java   (Java)
│   handler.py                (Python)
├── build.gradle / Dockerfile
├── function.yaml
└── payloads/
    ├── happy-path.json
    └── missing-input.json
```

For non-interactive use (CI):

```bash
./scripts/fn-init.sh greet --lang java --yes
./scripts/fn-init.sh greet --lang python --yes
```

---

## Step 2 — Implement the handler

### Java

Edit `src/main/java/.../GreetHandler.java`:

```java
@Override
public Object handle(InvocationRequest request) {
    @SuppressWarnings("unchecked")
    Map<String, Object> input = (Map<String, Object>) request.input();
    String name = (String) input.getOrDefault("name", "world");
    return Map.of("greeting", "Hello, " + name + "!");
}
```

### Python

Edit `handler.py`:

```python
@nanofaas_function
def handle(input_data):
    name = input_data.get("name", "world") if isinstance(input_data, dict) else "world"
    return {"greeting": f"Hello, {name}!"}
```

---

## Step 3 — Update the payloads

Edit `payloads/happy-path.json` to match your handler's actual input/output:

```json
{
  "description": "greet with explicit name",
  "input": {"name": "Alice"},
  "expected": {"greeting": "Hello, Alice!"}
}
```

---

## Step 4 — Run unit tests

### Java

```bash
./gradlew :examples:java:greet:test
```

### Python

```bash
uv run pytest
```

---

## Step 5 — Deploy

```bash
nanofaas deploy -f function.yaml
```

This builds the container image and registers the function on the control plane.

---

## Step 6 — Invoke

```bash
nanofaas invoke greet -d @payloads/happy-path.json
```

Expected response:

```json
{"greeting": "Hello, Alice!"}
```

---

## Step 7 — Run contract tests

```bash
nanofaas fn test greet --payloads ./payloads/
```

Runs every payload file against the deployed function and compares responses
to `expected`. *(Requires `nanofaas fn test` — see CLI docs for availability.)*

---

## Step 8 — Invoke asynchronously (optional)

```bash
nanofaas enqueue greet -d @payloads/happy-path.json
# returns {"executionId": "..."}

nanofaas exec get <executionId> --watch
```

---

## What's next

- Add more payload cases in `payloads/` for edge cases and error paths.
- Deploy to Kubernetes: see `docs/k8s.md`.
- Run a full E2E load test: `docs/e2e-tutorial.md`.
```

- [ ] **Step 2: Remove old tutorial**

```bash
git rm docs/tutorial-java-function.md
```

- [ ] **Step 3: Commit**

```bash
git add docs/tutorial-function.md
git commit -m "docs: replace java-only tutorial with unified Java+Python tutorial"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `tools/fn-init/` uv project | Task 1 |
| `scripts/fn-init.sh` wrapper | Task 9 |
| Rich interactive wizard | Task 7, 8 |
| Non-interactive `--yes` mode | Task 8 |
| Output dir logic + monorepo detection | Task 3 |
| `settings.gradle` update (Java, idempotent) | Task 3 |
| Java templates (6 files) | Task 4 |
| Python templates (4 files) | Task 5 |
| VS Code templates (optional, 6 files) | Task 6 |
| Payloads (happy-path + missing-input + assets/) | Task 3 |
| Payload format (JSON inline / @ref / base64) | Task 3 (happy-path.json format) |
| Next steps panel | Task 7 |
| Existing directory aborts with error | Task 3, 8 |
| Unified tutorial (Java + Python) | Task 10 |
| TDD throughout | Tasks 2–6 |
