from __future__ import annotations

from workflow_tasks.components.remote_script import k8s_e2e_test_vm_script


def test_script_has_cd_prefix_and_gradle_invocation() -> None:
    script = k8s_e2e_test_vm_script(
        remote_dir="/home/ubuntu/nanofaas",
        kubeconfig_path="/home/ubuntu/.kube/config",
        runtime_image="reg:5000/nanofaas/function-runtime:e2e",
        namespace="nf",
    )
    assert script.startswith("cd /home/ubuntu/nanofaas && ")
    assert "KUBECONFIG=/home/ubuntu/.kube/config" in script
    assert "FUNCTION_RUNTIME_IMAGE=reg:5000/nanofaas/function-runtime:e2e" in script
    assert "NANOFAAS_E2E_NAMESPACE=nf" in script
    assert ":control-plane-modules:k8s-deployment-provider:test" in script
    assert "K8sE2eTest" in script
    assert "scenarioManifest" not in script


def test_script_includes_manifest_property_when_present() -> None:
    script = k8s_e2e_test_vm_script(
        remote_dir="/r", kubeconfig_path="/k", runtime_image="img", namespace="nf",
        remote_manifest_path="/r/manifests/x.yml",
    )
    assert "-Dnanofaas.e2e.scenarioManifest=/r/manifests/x.yml" in script
