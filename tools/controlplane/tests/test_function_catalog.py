from pathlib import Path

import pytest

from controlplane_tool.function_catalog import (
    _discover_example_functions,
    list_function_presets,
    list_functions,
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
