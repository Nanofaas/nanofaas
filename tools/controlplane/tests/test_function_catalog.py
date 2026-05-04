from pathlib import Path

import pytest

from controlplane_tool.functions.catalog import (
    _discover_example_functions,
    list_function_presets,
    list_functions,
    resolve_function_definition,
    resolve_function_preset,
)


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_function_catalog_exposes_demo_families() -> None:
    keys = [function.key for function in list_functions()]
    assert "word-stats-java" in keys
    assert "json-transform-java" in keys
    assert "word-stats-javascript" in keys
    assert "json-transform-javascript" in keys
    assert "word-stats-go" in keys
    assert "json-transform-python" in keys
    assert "tool-metrics-echo" in keys


def test_function_catalog_discovers_repository_examples() -> None:
    keys = {function.key for function in list_functions()}

    assert {
        "word-stats-java",
        "json-transform-java",
        "word-stats-java-lite",
        "json-transform-java-lite",
        "word-stats-go",
        "json-transform-go",
        "word-stats-python",
        "json-transform-python",
        "word-stats-javascript",
        "json-transform-javascript",
        "word-stats-exec",
        "json-transform-exec",
        "roman-numeral-java",
        "roman-numeral-go",
        "roman-numeral-python",
        "roman-numeral-exec",
        "tool-metrics-echo",
    }.issubset(keys)
    assert "building-java" not in keys


def test_resolve_function_definition_uses_dynamic_index() -> None:
    function = resolve_function_definition("roman-numeral-go")

    assert function.family == "roman-numeral"
    assert function.runtime == "go"
    assert function.example_dir is not None
    assert function.example_dir.as_posix().endswith("examples/go/roman-numeral")


def test_dynamic_catalog_preserves_existing_metadata() -> None:
    function = resolve_function_definition("word-stats-java")

    assert function.description == "Spring Boot Java word statistics demo."
    assert function.default_image == "localhost:5000/nanofaas/java-word-stats:e2e"
    assert function.default_payload_file == "word-stats-sample.json"


def test_dynamic_catalog_exposes_manifest_backed_roman_numeral_details() -> None:
    function = resolve_function_definition("roman-numeral-java")

    assert function.description == "Java roman numeral conversion demo."
    assert function.default_image == "localhost:5000/nanofaas/java-roman-numeral:e2e"
    assert function.default_payload_file is None


def test_demo_java_preset_contains_only_java_functions() -> None:
    preset = resolve_function_preset("demo-java")
    assert {function.runtime for function in preset.functions} == {"java"}
    assert {function.family for function in preset.functions} == {"word-stats", "json-transform"}


def test_demo_javascript_preset_contains_only_javascript_functions() -> None:
    preset = resolve_function_preset("demo-javascript")
    assert {function.runtime for function in preset.functions} == {"javascript"}
    assert [function.key for function in preset.functions] == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_metrics_smoke_preset_contains_metrics_fixture() -> None:
    preset_names = [preset.name for preset in list_function_presets()]
    assert "metrics-smoke" in preset_names

    preset = resolve_function_preset("metrics-smoke")
    assert [function.key for function in preset.functions] == ["tool-metrics-echo"]


def test_demo_loadtest_preset_excludes_go_functions() -> None:
    preset = resolve_function_preset("demo-loadtest")

    assert "go" not in {function.runtime for function in preset.functions}
    assert "javascript" not in {function.runtime for function in preset.functions}
    assert {function.runtime for function in preset.functions} == {
        "java",
        "java-lite",
        "python",
        "exec",
    }


def test_discovers_function_from_manifest_catalog_metadata(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(payloads / "roman-numeral-sample.json", "{}")
    _write(
        examples / "go" / "roman-numeral" / "function.yaml",
        """
name: roman-numeral
image: nanofaas/roman-numeral:latest
catalog:
  family: roman-numeral
  runtime: go
  description: Go roman numeral demo.
  defaultImage: localhost:5000/nanofaas/go-roman-numeral:e2e
  defaultPayload: roman-numeral-sample.json
""".strip(),
    )

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["roman-numeral-go"]
    function = functions[0]
    assert function.family == "roman-numeral"
    assert function.runtime == "go"
    assert function.description == "Go roman numeral demo."
    assert function.default_image == "localhost:5000/nanofaas/go-roman-numeral:e2e"
    assert function.default_payload_file == "roman-numeral-sample.json"
    assert function.example_dir == examples / "go" / "roman-numeral"


def test_discovers_function_with_convention_fallback(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "bash" / "word-stats" / "Dockerfile", "FROM scratch")
    _write(payloads / "word-stats-sample.json", "{}")

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["word-stats-exec"]
    function = functions[0]
    assert function.family == "word-stats"
    assert function.runtime == "exec"
    assert function.default_image == "localhost:5000/nanofaas/bash-word-stats:e2e"
    assert function.default_payload_file == "word-stats-sample.json"


def test_discovery_ignores_generated_build_directories(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "build" / "tmp.txt", "")
    _write(examples / "java" / "word-stats" / "Dockerfile", "FROM scratch")
    _write(examples / "java" / "word-stats" / "build" / "tmp.txt", "")
    _write(payloads / "word-stats-sample.json", "{}")

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["word-stats-java"]


def test_discovers_java_lite_function_with_convention_fallback(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "java" / "word-stats-lite" / "Dockerfile", "FROM scratch")
    _write(payloads / "word-stats-sample.json", "{}")

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["word-stats-java-lite"]
    function = functions[0]
    assert function.family == "word-stats"
    assert function.runtime == "java-lite"
    assert function.default_image == "localhost:5000/nanofaas/java-lite-word-stats:e2e"
    assert function.default_payload_file == "word-stats-sample.json"


def test_discovery_rejects_duplicate_keys(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "go" / "same" / "Dockerfile", "FROM scratch")
    _write(
        examples / "go" / "other" / "function.yaml",
        """
catalog:
  family: same
  runtime: go
  description: duplicate
""".strip(),
    )

    with pytest.raises(ValueError, match="Duplicate function key: same-go"):
        _discover_example_functions(examples, payloads)


def test_discovery_rejects_non_mapping_catalog_metadata(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(
        examples / "go" / "bad" / "function.yaml",
        """
catalog: invalid
""".strip(),
    )

    with pytest.raises(ValueError, match="Invalid function catalog metadata"):
        _discover_example_functions(examples, payloads)


def test_discovery_rejects_invalid_catalog_runtime(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(
        examples / "go" / "bad" / "function.yaml",
        """
catalog:
  runtime: ruby
""".strip(),
    )

    with pytest.raises(ValueError, match="Unsupported function runtime: ruby"):
        _discover_example_functions(examples, payloads)


def test_static_presets_resolve_against_dynamic_catalog() -> None:
    preset = resolve_function_preset("demo-java")

    assert [function.key for function in preset.functions] == [
        "word-stats-java",
        "json-transform-java",
    ]


def test_demo_all_does_not_auto_include_new_discovered_functions() -> None:
    preset = resolve_function_preset("demo-all")

    assert "roman-numeral-java" not in {function.key for function in preset.functions}
