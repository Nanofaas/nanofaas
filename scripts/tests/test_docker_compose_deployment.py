from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
DOCKERFILE = REPO_ROOT / "deploy" / "compose" / "Dockerfile"
PROMETHEUS = REPO_ROOT / "deploy" / "compose" / "prometheus.yml"
DOCKERIGNORE = REPO_ROOT / ".dockerignore"


def test_compose_control_plane_has_build_and_public_image_paths():
    compose = COMPOSE.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")

    assert "image: ${NANOFAAS_CONTROL_PLANE_IMAGE:-ghcr.io/miciav/nanofaas/control-plane:latest}" in compose
    assert "dockerfile: deploy/compose/Dockerfile" in compose
    assert ":control-plane:bootJar" in dockerfile
    assert "-PcontrolPlaneModules=" in dockerfile


def test_compose_build_accepts_an_explicit_control_plane_module_set():
    compose = COMPOSE.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")

    assert "NANOFAAS_CONTROL_PLANE_MODULES" in compose
    assert "ARG NANOFAAS_CONTROL_PLANE_MODULES" in dockerfile
    assert "-PcontrolPlaneModules=${NANOFAAS_CONTROL_PLANE_MODULES}" in dockerfile


def test_compose_grants_explicit_docker_access_and_uses_one_named_network():
    compose = COMPOSE.read_text(encoding="utf-8")

    assert 'user: "0:0"' in compose
    assert "/var/run/docker.sock:/var/run/docker.sock" in compose
    assert "NANOFAAS_CONTAINER_LOCAL_RUNTIME_ADAPTER: docker-java" in compose
    assert "NANOFAAS_CONTAINER_LOCAL_NETWORK_NAME: nanofaas" in compose
    assert "NANOFAAS_CONTAINER_LOCAL_CALLBACK_URL: http://control-plane:8080/v1/internal/executions" in compose
    assert "NANOFAAS_METRICS_PROFILE: advanced" in compose
    assert "name: nanofaas" in compose
    assert "registry:" not in compose


def test_compose_prometheus_scrapes_control_plane_management_port():
    compose = COMPOSE.read_text(encoding="utf-8")
    prometheus = PROMETHEUS.read_text(encoding="utf-8")

    assert "prom/prometheus:v3.9.1" in compose
    assert "prometheus.yml:/etc/prometheus/prometheus.yml:ro" in compose
    assert "control-plane:8081" in prometheus
    assert "metrics_path: /actuator/prometheus" in prometheus
    assert "app: nanofaas-control-plane" in prometheus


def test_compose_build_context_excludes_local_virtual_environments():
    assert "**/.venv" in DOCKERIGNORE.read_text(encoding="utf-8").splitlines()
