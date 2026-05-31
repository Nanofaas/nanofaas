from __future__ import annotations

from pathlib import Path

from controlplane_tool.building import image_matrix as im


def test_catalog_has_all_16_targets() -> None:
    assert set(im.IMAGE_MATRIX) == {
        "control-plane", "function-runtime",
        "java-word-stats", "java-json-transform",
        "java-lite-word-stats", "java-lite-json-transform",
        "go-word-stats", "go-json-transform",
        "python-word-stats", "python-json-transform",
        "javascript-word-stats", "javascript-json-transform",
        "watchdog", "bash-word-stats", "bash-json-transform",
    }


def test_select_targets_all_returns_sorted_catalog() -> None:
    assert im.select_targets("all") == sorted(im.IMAGE_MATRIX)


def test_select_targets_csv_subset() -> None:
    assert im.select_targets("watchdog,go-word-stats") == ["watchdog", "go-word-stats"]


def test_select_targets_unknown_raises() -> None:
    import pytest
    with pytest.raises(ValueError):
        im.select_targets("nope")


def test_image_reference_single_arch_no_suffix() -> None:
    assert im.image_reference("watchdog", "1.2.3", "amd64", use_arch_suffix=False) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3"


def test_image_reference_arch_suffix() -> None:
    assert im.image_reference("watchdog", "1.2.3", "arm64", use_arch_suffix=True) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3-arm64"


def test_image_reference_multi_never_suffixes() -> None:
    assert im.image_reference("watchdog", "1.2.3", "multi", use_arch_suffix=True) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3"


def test_native_image_build_args_env_override(monkeypatch) -> None:
    monkeypatch.setenv("NATIVE_IMAGE_BUILD_ARGS", "-Xfoo")
    assert im.resolve_native_image_build_args() == "-Xfoo"


def test_native_image_build_args_default(monkeypatch) -> None:
    monkeypatch.delenv("NATIVE_IMAGE_BUILD_ARGS", raising=False)
    monkeypatch.setenv("NATIVE_ACTIVE_PROCESSORS", "3")
    monkeypatch.setenv("NATIVE_IMAGE_XMX", "4g")
    assert im.resolve_native_image_build_args() == \
        "-H:+AddAllCharsets -J-Xmx4g -J-XX:ActiveProcessorCount=3"


def test_resolve_current_version_reads_build_gradle(tmp_path) -> None:
    (tmp_path / "build.gradle").write_text("group = 'x'\nversion = '0.9.1'\n", encoding="utf-8")
    assert im.resolve_current_version(tmp_path) == "0.9.1"


def test_plan_build_docker_target_amd64() -> None:
    cmd = im.plan_build_command(Path("/repo"), "watchdog", "ghcr.io/x/watchdog:1", "amd64")
    assert cmd.command == [
        "docker", "build", "--platform", "linux/amd64",
        "--label", "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t", "ghcr.io/x/watchdog:1", "-f", "watchdog/Dockerfile", ".",
    ]
    assert cmd.cwd == Path("/repo")


def test_plan_build_docker_target_multi_uses_buildx() -> None:
    cmd = im.plan_build_command(Path("/repo"), "go-word-stats", "ghcr.io/x/go:1", "multi")
    assert cmd.command[:3] == ["docker", "buildx", "build"]
    assert "linux/arm64,linux/amd64" in cmd.command


def test_plan_build_gradle_target_sets_native_env() -> None:
    cmd = im.plan_build_command(Path("/repo"), "function-runtime", "ghcr.io/x/fr:1", "amd64")
    assert cmd.command[0] == "./gradlew"
    assert ":function-runtime:bootBuildImage" in cmd.command
    assert "-PfunctionRuntimeImage=ghcr.io/x/fr:1" in cmd.command
    assert "-PimagePlatform=linux/amd64" in cmd.command
    assert "NATIVE_IMAGE_BUILD_ARGS" in cmd.env


def test_control_plane_gradle_selects_all_modules() -> None:
    cmd = im.plan_build_command(Path("/repo"), "control-plane", "ghcr.io/x/cp:1", "amd64")
    assert ":control-plane:bootBuildImage" in cmd.command
    assert "-PcontrolPlaneModules=all" in cmd.command


def test_non_profile_gradle_target_omits_module_selection() -> None:
    cmd = im.plan_build_command(Path("/repo"), "function-runtime", "ghcr.io/x/fr:1", "amd64")
    assert "-PcontrolPlaneModules=all" not in cmd.command


def test_plan_build_gradle_arm64_adds_tiny_builder() -> None:
    cmd = im.plan_build_command(Path("/repo"), "java-word-stats", "ghcr.io/x/jw:1", "arm64")
    assert "-PimageBuilder=dashaun/builder:tiny" in cmd.command
    assert "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest" in cmd.command


def test_plan_push_command() -> None:
    cmd = im.plan_push_command(Path("/repo"), "ghcr.io/x/watchdog:1", runtime="docker")
    assert cmd.command == ["docker", "push", "ghcr.io/x/watchdog:1"]


def test_control_plane_is_marked_profile_target() -> None:
    assert im.IMAGE_MATRIX["control-plane"].profile_aware is True
    assert im.IMAGE_MATRIX["function-runtime"].profile_aware is False


def test_run_image_matrix_dry_run_records_build_then_push(monkeypatch) -> None:
    from workflow_tasks.shell import RecordingShell
    from shellcraft.runners import CommandRunner

    monkeypatch.delenv("NATIVE_IMAGE_BUILD_ARGS", raising=False)
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    im.run_image_matrix(
        runner=runner, repo_root=Path("/repo"),
        targets=["watchdog"], tag="9.9.9", arch="amd64",
        use_arch_suffix=False, push=True, runtime="docker", dry_run=True,
    )

    assert ["docker", "build", "--platform", "linux/amd64",
            "--label", "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
            "-t", "ghcr.io/miciav/nanofaas/watchdog:9.9.9", "-f", "watchdog/Dockerfile", "."] in shell.commands
    assert ["docker", "push", "ghcr.io/miciav/nanofaas/watchdog:9.9.9"] in shell.commands


def test_run_image_matrix_no_push_skips_push() -> None:
    from workflow_tasks.shell import RecordingShell
    from shellcraft.runners import CommandRunner

    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    im.run_image_matrix(
        runner=runner, repo_root=Path("/repo"),
        targets=["watchdog"], tag="1", arch="amd64",
        use_arch_suffix=False, push=False, runtime="docker", dry_run=True,
    )
    assert not any(c[:2] == ["docker", "push"] for c in shell.commands)
