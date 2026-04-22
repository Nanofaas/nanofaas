from __future__ import annotations

from controlplane_tool.scenario_tasks import (
    build_core_images_vm_script,
    build_function_images_vm_script,
    helm_upgrade_install_vm_script,
    kubectl_create_namespace_vm_script,
)


def test_build_core_images_vm_script_includes_pushes_and_remote_directory() -> None:
    script = build_core_images_vm_script(
        remote_dir="/srv/nanofaas",
        control_image="localhost:5000/nanofaas/control-plane:e2e",
        runtime_image="localhost:5000/nanofaas/function-runtime:e2e",
        runtime="java",
        mode="docker",
    )

    assert "cd /srv/nanofaas" in script
    assert "docker push localhost:5000/nanofaas/control-plane:e2e" in script
    assert "docker push localhost:5000/nanofaas/function-runtime:e2e" in script


def test_helm_upgrade_install_vm_script_uses_helm_ops_planner() -> None:
    script = helm_upgrade_install_vm_script(
        remote_dir="/srv/nanofaas",
        release="control-plane",
        chart="helm/nanofaas",
        namespace="nanofaas-e2e",
        values={"controlPlane.image.tag": "e2e"},
    )

    assert "cd /srv/nanofaas" in script
    assert "helm upgrade --install control-plane helm/nanofaas -n nanofaas-e2e" in script
    assert "--set controlPlane.image.tag=e2e" in script


def test_cluster_scripts_bind_explicit_kubeconfig() -> None:
    helm_script = helm_upgrade_install_vm_script(
        remote_dir="/srv/nanofaas",
        release="control-plane",
        chart="helm/nanofaas",
        namespace="nanofaas-e2e",
        values={"controlPlane.image.tag": "e2e"},
        kubeconfig_path="/home/ubuntu/.kube/config",
    )
    namespace_script = kubectl_create_namespace_vm_script(
        remote_dir="/srv/nanofaas",
        namespace="nanofaas-e2e",
        kubeconfig_path="/home/ubuntu/.kube/config",
    )

    assert "KUBECONFIG=/home/ubuntu/.kube/config helm upgrade" in helm_script
    assert "KUBECONFIG=/home/ubuntu/.kube/config kubectl create namespace" in namespace_script
    assert "| KUBECONFIG=/home/ubuntu/.kube/config kubectl apply -f -" in namespace_script


def test_build_function_images_vm_script_supports_javascript_dockerfiles() -> None:
    script = build_function_images_vm_script(
        remote_dir="/srv/nanofaas",
        functions=[("localhost:5000/nanofaas/javascript-word-stats:e2e", "javascript", "word-stats")],
    )

    assert "examples/javascript/word-stats/Dockerfile" in script
