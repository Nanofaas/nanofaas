from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ScenarioDeploymentDefaults:
    namespace: str | None = None
    release: str | None = None


_DEFAULTS: dict[str, ScenarioDeploymentDefaults] = {
    "cli": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
    "cli-stack": ScenarioDeploymentDefaults(
        namespace="nanofaas-cli-stack-e2e",
        release="nanofaas-cli-stack-e2e",
    ),
    "cli-host": ScenarioDeploymentDefaults(
        namespace="nanofaas-host-cli-e2e",
        release="nanofaas-host-cli-e2e",
    ),
    "helm-stack": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
    "k3s-junit-curl": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
}


def scenario_deployment_defaults(scenario: str) -> ScenarioDeploymentDefaults:
    return _DEFAULTS.get(scenario, ScenarioDeploymentDefaults())


def resolve_scenario_namespace(
    scenario: str,
    *,
    explicit_namespace: str | None,
    resolved_scenario_namespace: str | None,
) -> str | None:
    if explicit_namespace:
        return explicit_namespace
    if resolved_scenario_namespace:
        return resolved_scenario_namespace
    return scenario_deployment_defaults(scenario).namespace


def resolve_scenario_release(
    scenario: str,
    *,
    explicit_release: str | None,
) -> str | None:
    if explicit_release:
        return explicit_release
    return scenario_deployment_defaults(scenario).release
