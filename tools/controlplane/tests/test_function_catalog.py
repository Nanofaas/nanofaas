from controlplane_tool.function_catalog import (
    list_function_presets,
    list_functions,
    resolve_function_preset,
)


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
