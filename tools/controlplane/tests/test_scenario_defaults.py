from controlplane_tool.scenario.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
    scenario_deployment_defaults,
)


def test_cli_stack_defaults_are_isolated() -> None:
    defaults = scenario_deployment_defaults("cli-stack")

    assert defaults.namespace == "nanofaas-cli-stack-e2e"
    assert defaults.release == "nanofaas-cli-stack-e2e"


def test_cli_host_defaults_are_host_scoped() -> None:
    defaults = scenario_deployment_defaults("cli-host")

    assert defaults.namespace == "nanofaas-host-cli-e2e"
    assert defaults.release == "nanofaas-host-cli-e2e"


def test_resolve_namespace_prefers_explicit_then_resolved_then_default() -> None:
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace="custom",
        resolved_scenario_namespace="ignored",
    ) == "custom"
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace=None,
        resolved_scenario_namespace="from-scenario",
    ) == "from-scenario"
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace=None,
        resolved_scenario_namespace=None,
    ) == "nanofaas-cli-stack-e2e"


def test_resolve_release_prefers_explicit_then_default() -> None:
    assert resolve_scenario_release("cli-stack", explicit_release="custom") == "custom"
    assert resolve_scenario_release("helm-stack", explicit_release=None) == "control-plane"


def test_unknown_scenario_returns_empty_defaults() -> None:
    defaults = scenario_deployment_defaults("unknown-scenario")

    assert defaults.namespace is None
    assert defaults.release is None


def test_empty_namespace_falls_back_to_resolved_or_default() -> None:
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace="",
        resolved_scenario_namespace="from-scenario",
    ) == "from-scenario"
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace="",
        resolved_scenario_namespace=None,
    ) == "nanofaas-cli-stack-e2e"


def test_empty_release_falls_back_to_default() -> None:
    assert resolve_scenario_release("cli-stack", explicit_release="") == "nanofaas-cli-stack-e2e"
