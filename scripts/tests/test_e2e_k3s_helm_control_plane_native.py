from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-k3s-helm.sh"
PROM_CONFIG_TEMPLATE = REPO_ROOT / "helm" / "nanofaas" / "templates" / "prometheus-configmap.yaml"
PROM_RBAC_TEMPLATE = REPO_ROOT / "helm" / "nanofaas" / "templates" / "prometheus-rbac.yaml"
CADVISOR_DAEMONSET_TEMPLATE = REPO_ROOT / "helm" / "nanofaas" / "templates" / "cadvisor-daemonset.yaml"
CADVISOR_SERVICE_TEMPLATE = REPO_ROOT / "helm" / "nanofaas" / "templates" / "cadvisor-service.yaml"


def test_k3s_helm_script_is_now_a_controlplane_wrapper():
    script = SCRIPT.read_text(encoding="utf-8")
    assert 'controlplane.sh" e2e run helm-stack "$@"' in script
    assert "CONTROL_PLANE_NATIVE_BUILD" not in script
    assert "LOADTEST_RUNTIMES" not in script
    assert "docker save" not in script


def test_prometheus_templates_support_container_metrics_modes():
    prom_cfg = PROM_CONFIG_TEMPLATE.read_text(encoding="utf-8")
    prom_rbac = PROM_RBAC_TEMPLATE.read_text(encoding="utf-8")
    cadvisor_ds = CADVISOR_DAEMONSET_TEMPLATE.read_text(encoding="utf-8")
    cadvisor_svc = CADVISOR_SERVICE_TEMPLATE.read_text(encoding="utf-8")

    assert "/metrics/cadvisor" in prom_cfg
    assert "container_cpu_usage_seconds_total" in prom_cfg
    assert "container_memory_working_set_bytes" in prom_cfg
    assert ".Values.prometheus.containerMetrics.enabled" in prom_cfg
    assert "eq $containerMetricsMode \"kubelet\"" in prom_cfg
    assert "eq $containerMetricsMode \"daemonset\"" in prom_cfg
    assert "job_name: kubernetes-cadvisor" in prom_cfg
    assert "job_name: nanofaas-cadvisor" in prom_cfg

    assert "$containerMetricsKubeletEnabled" in prom_rbac
    assert "nodes/proxy" in prom_rbac

    assert "kind: DaemonSet" in cadvisor_ds
    assert "name: nanofaas-cadvisor" in cadvisor_ds
    assert ".Values.prometheus.containerMetrics.daemonset.image" in cadvisor_ds

    assert "kind: Service" in cadvisor_svc
    assert "name: nanofaas-cadvisor" in cadvisor_svc
