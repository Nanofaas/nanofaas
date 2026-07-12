from controlplane_tool.tui.app import NanofaasTUI
from controlplane_tool.workspace.paths import default_tool_paths


def test_tui_plans_the_same_scenario_and_environment_as_cli(capsys) -> None:
    tool_root = default_tool_paths().tool_root
    answers = iter(
        [
            str(tool_root / "scenarios-v2/validate-k8s.yaml"),
            str(tool_root / "environments/local.yaml"),
            "plan",
        ]
    )

    NanofaasTUI(choose=lambda message, choices: next(answers)).run()

    output = capsys.readouterr().out
    assert "images.build.word-stats-java" in output
    assert "resources.inspect.k8s.word-stats-java" in output
