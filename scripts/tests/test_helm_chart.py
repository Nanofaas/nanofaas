from __future__ import annotations

import math
import re
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CHART = REPO_ROOT / "deploy" / "helm" / "nanofaas"


def render(template: str, *extra_args: str) -> str:
    """Render a single chart template with `helm template`, returning its text."""
    result = subprocess.run(
        ["helm", "template", "nanofaas", str(CHART), "--show-only", template, *extra_args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def render_all(*extra_args: str) -> str:
    """Render the whole chart, returning the concatenated manifest text."""
    result = subprocess.run(
        ["helm", "template", "nanofaas", str(CHART), *extra_args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def test_control_plane_pvc_is_enabled_by_default():
    out = render("templates/control-plane-pvc.yaml")
    assert "kind: PersistentVolumeClaim" in out
    assert "name: nanofaas-control-plane-data" in out
    assert 'storage: "1Gi"' in out


def test_control_plane_pvc_size_and_storage_class_are_configurable():
    out = render(
        "templates/control-plane-pvc.yaml",
        "--set",
        "controlPlane.persistence.size=2Gi",
        "--set",
        "controlPlane.persistence.storageClass=fast",
    )
    assert 'storage: "2Gi"' in out
    assert 'storageClassName: "fast"' in out


def test_existing_claim_suppresses_new_control_plane_pvc():
    out = render_all("--set", "controlPlane.persistence.existingClaim=my-claim")
    assert "name: nanofaas-control-plane-data" not in out
    assert "claimName: \"my-claim\"" in out


def test_control_plane_deployment_mounts_registry_volume_and_sets_env():
    out = render("templates/control-plane-deployment.yaml")
    assert "mountPath: /var/lib/nanofaas" in out
    assert "name: NANOFAAS_REGISTRY_PATH" in out
    assert "value: /var/lib/nanofaas/functions.json" in out
    assert 'claimName: "nanofaas-control-plane-data"' in out


def test_control_plane_deployment_uses_existing_claim_when_set():
    out = render(
        "templates/control-plane-deployment.yaml",
        "--set",
        "controlPlane.persistence.existingClaim=my-claim",
    )
    assert 'claimName: "my-claim"' in out


def test_control_plane_runs_as_distroless_nonroot_with_fsgroup():
    out = render("templates/control-plane-deployment.yaml")
    assert "runAsUser: 65532" in out
    assert "runAsGroup: 65532" in out
    assert "fsGroup: 65532" in out


def test_control_plane_replica_count_defaults_to_one():
    out = render("templates/control-plane-deployment.yaml")
    assert "replicas: 1" in out


def test_invocation_capacity_env_values_render_as_integers():
    """A capacity the control plane binds to `long` must not render as a float.

    Helm decodes values.yaml through JSON, so a plain integer arrives as a
    float64 and `quote` prints large ones in scientific notation: 1048576
    becomes "1.048576e+06". The control plane refuses to start on that, the
    deployment crash-loops, and a Helm install dies on its timeout. The render
    is valid YAML either way, so nothing but the value itself gives it away.
    """
    out = render("templates/control-plane-deployment.yaml")
    rendered = re.findall(
        r"name: (NANOFAAS_INVOCATION_CAPACITY_\w+)\n\s+value: \"([^\"]+)\"", out
    )
    assert rendered, "no invocation-capacity env vars found in the deployment"
    assert [value for _, value in rendered if not value.isdigit()] == []


def test_io_worker_count_reaches_both_jvm_and_native_images():
    """A native binary ignores JAVA_TOOL_OPTIONS, so the count must also be an argument.

    Before the argument existed a native control plane at a one-core limit ran
    reactor-netty's default four event loops, not the one the chart derives.
    """
    for extra, expected in [((), "1"), (("--set", "controlPlane.resources.limits.cpu=1500m"), "2"),
                            (("--set", "controlPlane.jvm.ioWorkerCount=3"), "3")]:
        out = render("templates/control-plane-deployment.yaml", *extra)
        flag = f'-Dreactor.netty.ioWorkerCount={expected}"'
        assert re.search(r"args:\n\s+- \"" + re.escape(flag), out), out
        assert re.search(r"name: JAVA_TOOL_OPTIONS\n\s+value: \"" + re.escape(flag), out), out


def test_callback_url_targets_this_releases_service_and_namespace():
    """Function pods call back on this URL; application.yml's default names `default`.

    A release in the chart's own `nanofaas` namespace got the `default` URL, and every
    callback from every function pod failed with an I/O error.
    """
    def callback(*extra):
        out = render("templates/control-plane-deployment.yaml", *extra)
        match = re.search(r"name: NANOFAAS_K8S_CALLBACKURL\n\s+value: \"([^\"]+)\"", out)
        assert match, out
        return match.group(1)

    assert callback() == "http://control-plane.nanofaas.svc.cluster.local:8080/v1/internal/executions"
    assert callback("--set", "namespace.name=faas", "--set", "controlPlane.service.name=cp",
                    "--set", "controlPlane.service.ports.http=9090") \
        == "http://cp.faas.svc.cluster.local:9090/v1/internal/executions"
    assert callback("--set", "namespace.create=false", "--namespace", "team-a") \
        == "http://control-plane.team-a.svc.cluster.local:8080/v1/internal/executions"
    assert callback("--set", "controlPlane.callbackUrl=http://proxy:1/cb") == "http://proxy:1/cb"


def test_raw_manifest_io_worker_count_matches_its_cpu_limit():
    """deploy/k8s is static, so its loop count must track its own limit by the chart's rule."""
    manifest = (REPO_ROOT / "deploy" / "k8s" / "control-plane-deployment.yaml").read_text()
    cpu = re.search(r"limits:\n\s+cpu: \"?([0-9.]+m?)\"?", manifest).group(1)
    cores = float(cpu[:-1]) / 1000 if cpu.endswith("m") else float(cpu)
    expected = f"-Dreactor.netty.ioWorkerCount={max(1, math.ceil(cores))}"
    assert re.search(r"args:\n\s+- \"" + re.escape(expected) + "\"", manifest), expected
    assert re.search(r"name: JAVA_TOOL_OPTIONS\n\s+value: \"" + re.escape(expected) + "\"", manifest), expected


def test_no_rendered_number_uses_scientific_notation():
    """The guard for whatever capacity the chart grows next."""
    assert re.search(r"[0-9]e[+-][0-9]", render_all()) is None
