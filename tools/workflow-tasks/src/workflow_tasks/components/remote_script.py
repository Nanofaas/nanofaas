"""Pure remote-shell script builders used by scenario components."""
from __future__ import annotations

import shlex

__all__ = ["k8s_e2e_test_vm_script"]


def k8s_e2e_test_vm_script(
    *,
    remote_dir: str,
    kubeconfig_path: str,
    runtime_image: str,
    namespace: str,
    remote_manifest_path: str | None = None,
) -> str:
    manifest_property = ""
    if remote_manifest_path is not None:
        manifest_property = f"-Dnanofaas.e2e.scenarioManifest={shlex.quote(remote_manifest_path)} "
    command = (
        f"KUBECONFIG={shlex.quote(kubeconfig_path)} "
        f"FUNCTION_RUNTIME_IMAGE={shlex.quote(runtime_image)} "
        f"NANOFAAS_E2E_NAMESPACE={shlex.quote(namespace)} "
        f"./gradlew :control-plane-modules:k8s-deployment-provider:test "
        f"{manifest_property}-PrunE2e --tests "
        "it.unimib.datai.nanofaas.modules.k8s.e2e.K8sE2eTest --no-daemon"
    )
    return f"cd {shlex.quote(remote_dir)} && {command}"
