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
    resolve_sdk_dependency_spec,
    generate_function,
    update_settings_gradle,
)
from fn_init import wizard


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

def test_resolve_in_monorepo_with_out_already_pointing_to_target_dir(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    target = tmp_path / "examples" / "python" / "greet"
    out, root = resolve_output_dir("greet", "python", target, tmp_path)
    assert out == target
    assert root == tmp_path

def test_resolve_outside_monorepo_with_out(tmp_path):
    custom = tmp_path / "projects"
    out, root = resolve_output_dir("greet", "java", custom, tmp_path)
    assert out == custom / "greet"
    assert root is None

def test_resolve_outside_monorepo_no_out_raises(tmp_path):
    with pytest.raises(ValueError, match="--out"):
        resolve_output_dir("greet", "java", None, tmp_path)


def test_resolve_sdk_dependency_spec_inside_monorepo_uses_file_dependency(tmp_path):
    monorepo_root = tmp_path / "repo"
    output_dir = monorepo_root / "examples" / "javascript" / "greet"
    output_dir.mkdir(parents=True)
    assert resolve_sdk_dependency_spec(monorepo_root, output_dir, "0.16.1") == "file:../../../function-sdk-javascript"


def test_resolve_sdk_dependency_spec_outside_monorepo_uses_published_version(tmp_path):
    monorepo_root = tmp_path / "repo"
    output_dir = tmp_path / "generated" / "greet"
    output_dir.mkdir(parents=True)
    assert resolve_sdk_dependency_spec(monorepo_root, output_dir, "0.16.1") == "0.16.1"


def test_resolve_sdk_dependency_spec_without_monorepo_uses_published_version(tmp_path):
    output_dir = tmp_path / "generated" / "greet"
    output_dir.mkdir(parents=True)
    assert resolve_sdk_dependency_spec(None, output_dir, "0.16.1") == "0.16.1"


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
    assert (out / "pyproject.toml").exists()

def test_generate_python_pyproject_has_function_name(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "python", out, vscode=False, placeholders=PYTHON_PLACEHOLDERS)
    content = (out / "pyproject.toml").read_text()
    assert 'name = "greet"' in content
    assert 'pythonpath = ["."]' in content

def test_generate_python_creates_payloads(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "python", out, vscode=False, placeholders=PYTHON_PLACEHOLDERS)
    assert (out / "payloads" / "happy-path.json").exists()


# --- generate_function (Go) ---

GO_PLACEHOLDERS = {
    "FUNCTION_NAME": "greet",
    "CLASS_NAME": "Greet",
    "PACKAGE": "it.unimib.datai.nanofaas.examples.greet",
    "PACKAGE_PATH": "it/unimib/datai/nanofaas/examples/greet",
    "IMAGE_TAG": "nanofaas/greet:latest",
    "LANG": "go",
}

JAVASCRIPT_PLACEHOLDERS = {
    "FUNCTION_NAME": "greet",
    "CLASS_NAME": "Greet",
    "PACKAGE": "it.unimib.datai.nanofaas.examples.greet",
    "PACKAGE_PATH": "it/unimib/datai/nanofaas/examples/greet",
    "IMAGE_TAG": "nanofaas/greet:latest",
    "LANG": "javascript",
    "SDK_DEPENDENCY": "file:../../../function-sdk-javascript",
    "SDK_BUILD_HOOKS": (
        '    "prebuild": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\n'
        '    "pretest": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\n'
    ),
    "BUILD_CONTEXT": "../../..",
    "DOCKERFILE_PATH": "examples/javascript/greet/Dockerfile",
    "DOCKER_APP_COPY": "COPY examples/javascript/greet /src/examples/javascript/greet",
    "DOCKER_APP_DIR": "/src/examples/javascript/greet",
    "DOCKER_SDK_COPY": "COPY function-sdk-javascript ./function-sdk-javascript",
    "DOCKER_FINAL_SDK_COPY": "COPY --from=build /src/function-sdk-javascript /function-sdk-javascript",
}

def test_generate_go_creates_main(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "go", out, vscode=False, placeholders=GO_PLACEHOLDERS)
    assert (out / "main.go").exists()
    content = (out / "main.go").read_text()
    assert "handleGreet" in content
    assert "nanofaas.NewRuntime()" in content

def test_generate_go_creates_test(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "go", out, vscode=False, placeholders=GO_PLACEHOLDERS)
    assert (out / "main_test.go").exists()
    assert "TestHandleGreetReturnsResult" in (out / "main_test.go").read_text()

def test_generate_go_creates_build_files(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "go", out, vscode=False, placeholders=GO_PLACEHOLDERS)
    assert (out / "go.mod").exists()
    assert (out / "Dockerfile").exists()
    assert (out / "function.yaml").exists()

def test_generate_go_gomod_has_module(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "go", out, vscode=False, placeholders=GO_PLACEHOLDERS)
    content = (out / "go.mod").read_text()
    assert "github.com/miciav/nanofaas/examples/go/greet" in content
    assert "function-sdk-go" in content


# --- generate_function (JavaScript) ---

def test_generate_javascript_creates_sources(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "javascript", out, vscode=False, placeholders=JAVASCRIPT_PLACEHOLDERS)
    assert (out / "package.json").exists()
    assert (out / "tsconfig.json").exists()
    assert (out / "src" / "index.ts").exists()
    assert (out / "src" / "handler.ts").exists()
    assert (out / "test" / "handler.test.ts").exists()

def test_generate_javascript_package_keeps_local_sdk_inside_monorepo(tmp_path):
    out = tmp_path / "greet"
    placeholders = dict(JAVASCRIPT_PLACEHOLDERS)
    placeholders["SDK_DEPENDENCY"] = "file:../../../function-sdk-javascript"
    placeholders["SDK_BUILD_HOOKS"] = (
        '    "prebuild": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\n'
        '    "pretest": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\n'
    )
    generate_function("greet", "javascript", out, vscode=False, placeholders=placeholders)
    content = (out / "package.json").read_text()
    assert '"nanofaas-function-sdk": "file:../../../function-sdk-javascript"' in content
    assert '"prebuild": "npm --prefix ../../../function-sdk-javascript install && npm --prefix ../../../function-sdk-javascript run build"' in content
    assert '"pretest": "npm --prefix ../../../function-sdk-javascript install && npm --prefix ../../../function-sdk-javascript run build"' in content
    assert '"test": "npm run build && node --test dist/test/**/*.test.js"' in content


def test_generate_javascript_package_uses_published_sdk_outside_monorepo(tmp_path):
    out = tmp_path / "greet"
    placeholders = dict(JAVASCRIPT_PLACEHOLDERS)
    placeholders["SDK_DEPENDENCY"] = "0.16.1"
    placeholders["SDK_BUILD_HOOKS"] = ""
    generate_function("greet", "javascript", out, vscode=False, placeholders=placeholders)
    content = (out / "package.json").read_text()
    assert '"nanofaas-function-sdk": "0.16.1"' in content
    assert '"prebuild"' not in content
    assert '"pretest"' not in content

def test_generate_javascript_function_yaml_uses_javascript_dockerfile(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "javascript", out, vscode=False, placeholders=JAVASCRIPT_PLACEHOLDERS)
    content = (out / "function.yaml").read_text()
    assert "dockerfile: examples/javascript/greet/Dockerfile" in content

def test_generate_javascript_vscode(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "javascript", out, vscode=True, placeholders=JAVASCRIPT_PLACEHOLDERS)
    assert (out / ".vscode" / "settings.json").exists()
    assert (out / ".vscode" / "launch.json").exists()
    assert (out / ".vscode" / "extensions.json").exists()


# --- generate_function (Bash) ---

BASH_PLACEHOLDERS = {
    "FUNCTION_NAME": "greet",
    "CLASS_NAME": "Greet",
    "PACKAGE": "it.unimib.datai.nanofaas.examples.greet",
    "PACKAGE_PATH": "it/unimib/datai/nanofaas/examples/greet",
    "IMAGE_TAG": "nanofaas/greet:latest",
    "LANG": "bash",
}

def test_generate_bash_creates_handler(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "bash", out, vscode=False, placeholders=BASH_PLACEHOLDERS)
    handler = out / "handler.sh"
    assert handler.exists()
    assert "#!/usr/bin/env bash" in handler.read_text()

def test_generate_bash_handler_is_executable(tmp_path):
    import stat as stat_module
    out = tmp_path / "greet"
    generate_function("greet", "bash", out, vscode=False, placeholders=BASH_PLACEHOLDERS)
    mode = (out / "handler.sh").stat().st_mode
    assert mode & stat_module.S_IXUSR

def test_generate_bash_creates_test(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "bash", out, vscode=False, placeholders=BASH_PLACEHOLDERS)
    assert (out / "tests" / "test_handler.sh").exists()

def test_generate_bash_creates_build_files(tmp_path):
    out = tmp_path / "greet"
    generate_function("greet", "bash", out, vscode=False, placeholders=BASH_PLACEHOLDERS)
    assert (out / "Dockerfile").exists()
    assert (out / "function.yaml").exists()


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

def test_update_settings_gradle_javascript_noop(tmp_path):
    (tmp_path / "settings.gradle").write_text("")
    assert update_settings_gradle(tmp_path, "my-fn", "javascript") is False
    assert (tmp_path / "settings.gradle").read_text() == ""


# --- wizard ---

def test_show_next_steps_for_javascript_mentions_npm_commands(capsys):
    wizard.show_next_steps("greet", "javascript", Path("/tmp/greet"))
    captured = capsys.readouterr().out
    assert "npm install" in captured
    assert "npm test" in captured
    assert "npm run build" in captured
