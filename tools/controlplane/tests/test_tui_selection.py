from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.tui.selection import (
    TuiSelectionResult,
    TuiSelectionTarget,
    function_choices,
    preset_choices,
    saved_profile_choices,
    scenario_file_choices,
    selection_source_choices,
)


def cli_stack_target() -> TuiSelectionTarget:
    return TuiSelectionTarget(
        key="cli-stack",
        label="cli-stack",
        resolver_scenario="cli-stack",
        selection_mode="multi",
        allow_default=True,
        allow_presets=True,
        allow_single_functions=False,
        allow_scenario_files=True,
        allow_saved_profiles=True,
    )


def k3s_target() -> TuiSelectionTarget:
    return TuiSelectionTarget(
        key="k3s-junit-curl",
        label="k3s-junit-curl",
        resolver_scenario="k3s-junit-curl",
        selection_mode="multi",
        allow_default=True,
        allow_presets=True,
        allow_single_functions=False,
        allow_scenario_files=True,
        allow_saved_profiles=True,
        strict_base_scenarios=frozenset({"k3s-junit-curl"}),
    )


def container_local_target() -> TuiSelectionTarget:
    return TuiSelectionTarget(
        key="container-local",
        label="container-local",
        resolver_scenario="container-local",
        selection_mode="single",
        allow_default=True,
        allow_presets=False,
        allow_single_functions=True,
        allow_scenario_files=True,
        allow_saved_profiles=True,
    )


def single_function_preset_target() -> TuiSelectionTarget:
    return TuiSelectionTarget(
        key="single-preset-test",
        label="single-preset-test",
        resolver_scenario="container-local",
        selection_mode="single",
        allow_default=False,
        allow_presets=True,
        allow_single_functions=False,
        allow_scenario_files=False,
        allow_saved_profiles=False,
    )


def _paths(workspace_root: Path) -> SimpleNamespace:
    return SimpleNamespace(
        workspace_root=workspace_root,
        scenarios_dir=workspace_root / "tools" / "controlplane" / "scenarios",
    )


def _function(
    key: str,
    *,
    runtime: str = "javascript",
    image: str | None = "localhost:5000/nanofaas/function:e2e",
    example_dir: Path | None = Path("examples/javascript/function"),
) -> SimpleNamespace:
    return SimpleNamespace(
        key=key,
        runtime=runtime,
        image=image,
        example_dir=example_dir,
    )


def _scenario(
    *,
    base_scenario: str,
    function_keys: list[str],
    functions: list[object],
    name: str = "scenario",
) -> SimpleNamespace:
    return SimpleNamespace(
        name=name,
        base_scenario=base_scenario,
        function_keys=function_keys,
        functions=functions,
    )


def _profile(
    *,
    base_scenario: str | None = None,
    function_preset: str | None = None,
    functions: list[str] | None = None,
    scenario_file: str | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        scenario=SimpleNamespace(
            base_scenario=base_scenario,
            function_preset=function_preset,
            functions=functions or [],
            scenario_file=scenario_file,
        )
    )


def test_multi_function_target_exposes_default_preset_scenario_and_profile_sources() -> None:
    assert [choice.value for choice in selection_source_choices(cli_stack_target())] == [
        "default",
        "preset",
        "scenario-file",
        "saved-profile",
    ]


def test_single_function_target_exposes_function_not_preset_source() -> None:
    assert [choice.value for choice in selection_source_choices(container_local_target())] == [
        "default",
        "function",
        "scenario-file",
        "saved-profile",
    ]


def test_cli_stack_preset_choices_include_demo_javascript() -> None:
    values = [choice.value for choice in preset_choices(cli_stack_target())]

    assert "demo-javascript" in values


def test_single_function_preset_choices_hide_multi_function_presets() -> None:
    values = [choice.value for choice in preset_choices(single_function_preset_target())]

    assert "demo-javascript" not in values


def test_container_local_function_choices_include_javascript_functions_not_fixtures() -> None:
    values = [choice.value for choice in function_choices(container_local_target())]

    assert "word-stats-javascript" in values
    assert "json-transform-javascript" in values
    assert "tool-metrics-echo" not in values


def test_selection_result_exposes_resolver_kwargs() -> None:
    scenario_path = Path("tools/controlplane/scenarios/k8s-demo-javascript.toml")
    result = TuiSelectionResult(
        source="scenario-file",
        scenario_file=scenario_path,
    )

    assert result.as_resolver_kwargs() == {
        "function_preset": None,
        "functions_csv": None,
        "scenario_file": scenario_path,
        "saved_profile": None,
    }


def test_k3s_scenario_file_choices_keep_strict_base_scenario(monkeypatch, tmp_path: Path) -> None:
    import controlplane_tool.tui.selection as selection

    fake_paths = _paths(tmp_path)
    fake_paths.scenarios_dir.mkdir(parents=True)
    for name in ("k8s-demo-javascript.toml", "k8s-demo-all.toml", "broken.toml"):
        (fake_paths.scenarios_dir / name).write_text("", encoding="utf-8")

    def fake_load_scenario_file(path: Path):  # noqa: ANN001
        if path.name == "k8s-demo-javascript.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["word-stats-javascript", "json-transform-javascript"],
                functions=[
                    _function("word-stats-javascript"),
                    _function("json-transform-javascript"),
                ],
            )
        if path.name == "k8s-demo-all.toml":
            return _scenario(
                base_scenario="helm-stack",
                function_keys=["word-stats-java"],
                functions=[_function("word-stats-java", runtime="java")],
            )
        raise ValueError("invalid manifest")

    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(selection, "load_scenario_file", fake_load_scenario_file)

    values = [choice.value for choice in scenario_file_choices(k3s_target())]

    assert values == ["tools/controlplane/scenarios/k8s-demo-javascript.toml"]


def test_cli_stack_scenario_file_choices_reuse_buildable_cross_scenario_manifests(
    monkeypatch,
    tmp_path: Path,
) -> None:
    import controlplane_tool.tui.selection as selection

    fake_paths = _paths(tmp_path)
    fake_paths.scenarios_dir.mkdir(parents=True)
    for name in ("k8s-demo-javascript.toml", "fixture-only.toml", "broken.toml"):
        (fake_paths.scenarios_dir / name).write_text("", encoding="utf-8")

    def fake_load_scenario_file(path: Path):  # noqa: ANN001
        if path.name == "k8s-demo-javascript.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["word-stats-javascript", "json-transform-javascript"],
                functions=[
                    _function("word-stats-javascript"),
                    _function("json-transform-javascript"),
                ],
            )
        if path.name == "fixture-only.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["tool-metrics-echo"],
                functions=[
                    _function(
                        "tool-metrics-echo",
                        runtime="fixture",
                        image=None,
                        example_dir=None,
                    )
                ],
            )
        raise ValueError("invalid manifest")

    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(selection, "load_scenario_file", fake_load_scenario_file)

    values = [choice.value for choice in scenario_file_choices(cli_stack_target())]

    assert values == ["tools/controlplane/scenarios/k8s-demo-javascript.toml"]


def test_container_local_scenario_file_choices_require_exactly_one_function(
    monkeypatch,
    tmp_path: Path,
) -> None:
    import controlplane_tool.tui.selection as selection

    fake_paths = _paths(tmp_path)
    fake_paths.scenarios_dir.mkdir(parents=True)
    for name in (
        "single-word-stats-javascript.toml",
        "k8s-demo-javascript.toml",
        "fixture-only.toml",
        "broken.toml",
    ):
        (fake_paths.scenarios_dir / name).write_text("", encoding="utf-8")

    def fake_load_scenario_file(path: Path):  # noqa: ANN001
        if path.name == "single-word-stats-javascript.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["word-stats-javascript"],
                functions=[_function("word-stats-javascript")],
            )
        if path.name == "k8s-demo-javascript.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["word-stats-javascript", "json-transform-javascript"],
                functions=[
                    _function("word-stats-javascript"),
                    _function("json-transform-javascript"),
                ],
            )
        if path.name == "fixture-only.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["tool-metrics-echo"],
                functions=[
                    _function(
                        "tool-metrics-echo",
                        runtime="fixture",
                        image=None,
                        example_dir=None,
                    )
                ],
            )
        raise ValueError("invalid manifest")

    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(selection, "load_scenario_file", fake_load_scenario_file)

    values = [choice.value for choice in scenario_file_choices(container_local_target())]

    assert values == ["tools/controlplane/scenarios/single-word-stats-javascript.toml"]


def test_cli_stack_saved_profile_choices_show_cross_scenario_function_selection(
    monkeypatch,
) -> None:
    import controlplane_tool.tui.selection as selection

    monkeypatch.setattr(
        selection,
        "list_profiles",
        lambda: ["demo-javascript", "generic", "fixture-only", "broken"],
    )

    def fake_load_profile(name: str):  # noqa: ANN001
        if name == "demo-javascript":
            return _profile(
                base_scenario="k3s-junit-curl",
                function_preset="demo-javascript",
            )
        if name == "generic":
            return _profile(base_scenario="k3s-junit-curl")
        if name == "fixture-only":
            return _profile(
                base_scenario="k3s-junit-curl",
                function_preset="metrics-smoke",
            )
        raise ValueError("invalid profile")

    monkeypatch.setattr(selection, "load_profile", fake_load_profile)

    values = [choice.value for choice in saved_profile_choices(cli_stack_target())]

    assert values == ["demo-javascript"]


def test_container_local_saved_profile_choices_require_exactly_one_buildable_function(
    monkeypatch,
) -> None:
    import controlplane_tool.tui.selection as selection

    monkeypatch.setattr(
        selection,
        "list_profiles",
        lambda: ["single-javascript", "demo-javascript", "generic", "broken"],
    )

    def fake_load_profile(name: str):  # noqa: ANN001
        if name == "single-javascript":
            return _profile(functions=["word-stats-javascript"])
        if name == "demo-javascript":
            return _profile(function_preset="demo-javascript")
        if name == "generic":
            return _profile()
        raise ValueError("invalid profile")

    monkeypatch.setattr(selection, "load_profile", fake_load_profile)

    values = [choice.value for choice in saved_profile_choices(container_local_target())]

    assert values == ["single-javascript"]


def test_saved_profile_choices_resolve_scenario_file_selection(monkeypatch, tmp_path: Path) -> None:
    import controlplane_tool.tui.selection as selection

    fake_paths = _paths(tmp_path)
    scenario_file = fake_paths.scenarios_dir / "single-word-stats-javascript.toml"
    scenario_file.parent.mkdir(parents=True)
    scenario_file.write_text("", encoding="utf-8")

    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(selection, "list_profiles", lambda: ["single-scenario", "multi-scenario"])
    monkeypatch.setattr(
        selection,
        "load_profile",
        lambda name: _profile(
            scenario_file=(
                "tools/controlplane/scenarios/single-word-stats-javascript.toml"
                if name == "single-scenario"
                else "tools/controlplane/scenarios/k8s-demo-javascript.toml"
            ),
        ),
    )

    def fake_load_scenario_file(path: Path):  # noqa: ANN001
        if path.name == "single-word-stats-javascript.toml":
            return _scenario(
                base_scenario="k3s-junit-curl",
                function_keys=["word-stats-javascript"],
                functions=[_function("word-stats-javascript")],
            )
        return _scenario(
            base_scenario="k3s-junit-curl",
            function_keys=["word-stats-javascript", "json-transform-javascript"],
            functions=[
                _function("word-stats-javascript"),
                _function("json-transform-javascript"),
            ],
        )

    monkeypatch.setattr(selection, "load_scenario_file", fake_load_scenario_file)

    values = [choice.value for choice in saved_profile_choices(container_local_target())]

    assert values == ["single-scenario"]
