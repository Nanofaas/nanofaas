from __future__ import annotations

from pathlib import Path

import questionary
import typer

from controlplane_tool.models import (
    ControlPlaneConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    TestsConfig,
)
from controlplane_tool.metrics_contract import CORE_REQUIRED_METRICS
from controlplane_tool.module_catalog import module_choices
from controlplane_tool.profiles import save_profile

DEFAULT_REQUIRED_METRICS: tuple[str, ...] = CORE_REQUIRED_METRICS


def build_profile_interactive(profile_name: str) -> Profile:
    runtime = questionary.select(
        "Control plane implementation:",
        choices=[
            questionary.Choice("Java", value="java"),
            questionary.Choice("Rust", value="rust"),
        ],
        default="java",
    ).ask()
    if runtime is None:
        raise typer.Exit(code=1)

    build_mode = "rust"
    if runtime == "java":
        selected_mode = questionary.select(
            "Java build mode:",
            choices=[
                questionary.Choice("Native", value="native"),
                questionary.Choice("JVM", value="jvm"),
            ],
            default="native",
        ).ask()
        if selected_mode is None:
            raise typer.Exit(code=1)
        build_mode = selected_mode

    available_modules = module_choices()
    selected_modules = questionary.checkbox(
        "Select control-plane modules:",
        choices=[
            questionary.Choice(
                title=f"{module.name} - {module.description}",
                value=module.key,
            )
            for module in available_modules
        ],
    ).ask()
    if selected_modules is None:
        raise typer.Exit(code=1)

    tests_enabled = questionary.confirm("Run tests after build?", default=True).ask()
    if tests_enabled is None:
        raise typer.Exit(code=1)

    tests = TestsConfig(enabled=tests_enabled)
    if tests_enabled:
        tests.api = bool(questionary.confirm("Run API tests?", default=True).ask())
        tests.e2e_mockk8s = bool(
            questionary.confirm(
                "Run mock Kubernetes E2E tests?", default=True
            ).ask()
        )
        tests.metrics = bool(
            questionary.confirm(
                "Run metrics + k6 load tests with Prometheus?", default=True
            ).ask()
        )
        load_profile = questionary.select(
            "Load profile:",
            choices=[
                questionary.Choice("Quick", value="quick"),
                questionary.Choice("Stress", value="stress"),
            ],
            default="quick",
        ).ask()
        if load_profile is None:
            raise typer.Exit(code=1)
        tests.load_profile = load_profile

    return Profile(
        name=profile_name,
        control_plane=ControlPlaneConfig(implementation=runtime, build_mode=build_mode),
        modules=list(selected_modules),
        tests=tests,
        metrics=MetricsConfig(
            required=list(DEFAULT_REQUIRED_METRICS),
            prometheus_url=None,
            strict_required=False,
        ),
        report=ReportConfig(title=f"Control Plane run ({profile_name})"),
    )


def build_and_save_profile(profile_name: str) -> tuple[Profile, Path]:
    profile = build_profile_interactive(profile_name=profile_name)
    destination = save_profile(profile)
    return profile, destination
