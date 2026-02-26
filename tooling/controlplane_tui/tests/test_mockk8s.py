from controlplane_tool.mockk8s import default_mockk8s_test_selectors


def test_default_mockk8s_selectors_include_fabric8_targets() -> None:
    selectors = default_mockk8s_test_selectors()
    assert selectors
    assert any("KubernetesResourceManagerTest" in selector for selector in selectors)
    assert any("KubernetesDeploymentBuilderTest" in selector for selector in selectors)
