from __future__ import annotations


def default_mockk8s_test_selectors() -> list[str]:
    """Fabric8-backed test classes validating Deployment/ReplicaSet semantics."""
    return [
        "*KubernetesResourceManagerTest",
        "*KubernetesDeploymentBuilderTest",
    ]
