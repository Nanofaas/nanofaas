from __future__ import annotations

import pytest
from pydantic import ValidationError

from controlplane_tool.config.scenario import ScenarioConfig


@pytest.mark.parametrize("workflow", ["validate", "cli", "loadtest"])
def test_accepts_supported_workflows(workflow: str) -> None:
    config = ScenarioConfig.model_validate(
        {
            "workflow": workflow,
            "backend": "container" if workflow == "validate" else "k8s",
            "functions": ["word-stats-java"],
        }
    )

    assert config.workflow == workflow


def test_validate_requires_backend() -> None:
    with pytest.raises(ValidationError, match="backend is required"):
        ScenarioConfig(workflow="validate", functions=["word-stats-java"])


def test_resource_request_must_not_exceed_limit() -> None:
    with pytest.raises(ValidationError, match="resource request must not exceed limit"):
        ScenarioConfig.model_validate(
            {
                "workflow": "validate",
                "backend": "container",
                "functions": ["word-stats-java"],
                "resources": {
                    "word-stats-java": {
                        "requests": {"cpu": 1, "memoryMiB": 513},
                        "limits": {"cpu": 0.5, "memoryMiB": 512},
                    }
                },
            }
        )


def test_resources_must_refer_to_selected_functions() -> None:
    with pytest.raises(ValidationError, match="resources must refer to selected functions"):
        ScenarioConfig.model_validate(
            {
                "workflow": "validate",
                "backend": "container",
                "functions": ["word-stats-java"],
                "resources": {"other": {"limits": {"memoryMiB": 512}}},
            }
        )


def test_autoscaling_is_opt_in_for_loadtest() -> None:
    config = ScenarioConfig(
        workflow="loadtest", functions=["word-stats-java"], autoscaling=True
    )

    assert config.autoscaling is True


def test_autoscaling_is_rejected_outside_loadtest() -> None:
    with pytest.raises(ValidationError, match="autoscaling is only supported"):
        ScenarioConfig(
            workflow="validate",
            backend="k8s",
            functions=["word-stats-java"],
            autoscaling=True,
        )


def test_rejects_legacy_fields() -> None:
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        ScenarioConfig.model_validate(
            {
                "workflow": "validate",
                "backend": "container",
                "functions": ["word-stats-java"],
                "base_scenario": "validate-container-local",
            }
        )
