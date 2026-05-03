from typer.testing import CliRunner

from controlplane_tool.main import app


def test_functions_command_lists_known_presets() -> None:
    result = CliRunner().invoke(app, ["functions", "list"])
    assert result.exit_code == 0
    assert "demo-java" in result.stdout
    assert "demo-all" in result.stdout
    assert "word-stats-java" in result.stdout


def test_functions_list_includes_dynamic_roman_numeral_function() -> None:
    result = CliRunner().invoke(app, ["functions", "list"])

    assert result.exit_code == 0
    assert "roman-numeral-go" in result.stdout


def test_functions_show_renders_function_metadata() -> None:
    result = CliRunner().invoke(app, ["functions", "show", "word-stats-java"])
    assert result.exit_code == 0
    assert "word-stats-java" in result.stdout
    assert "word-stats" in result.stdout
    assert "java" in result.stdout


def test_functions_show_prints_dynamic_function_details() -> None:
    result = CliRunner().invoke(app, ["functions", "show", "roman-numeral-go"])

    assert result.exit_code == 0
    assert "roman-numeral-go" in result.stdout
    assert "Go roman numeral conversion demo." in result.stdout
    assert "localhost:5000/nanofaas/go-roman-numeral:e2e" in result.stdout
    assert "examples/go/roman-numeral" in result.stdout


def test_functions_show_preset_renders_function_list() -> None:
    result = CliRunner().invoke(app, ["functions", "show-preset", "demo-java"])
    assert result.exit_code == 0
    assert "demo-java" in result.stdout
    assert "word-stats-java" in result.stdout
    assert "json-transform-java" in result.stdout
    assert "word-stats-go" not in result.stdout


def test_functions_show_preset_renders_javascript_function_list() -> None:
    result = CliRunner().invoke(app, ["functions", "show-preset", "demo-javascript"])
    assert result.exit_code == 0
    assert "demo-javascript" in result.stdout
    assert "word-stats-javascript" in result.stdout
    assert "json-transform-javascript" in result.stdout
