from pathlib import Path


COMPOSE = Path(__file__).resolve().parents[2] / "deploy" / "compose" / "offload-loadtest.yaml"


def test_offload_loadtest_compose_has_isolated_edge_and_cloud_control_planes():
    compose = COMPOSE.read_text(encoding="utf-8")

    assert "edge-control-plane:" in compose
    assert "cloud-control-plane:" in compose
    assert '"8080:8080"' in compose
    assert '"8081:8081"' in compose
    assert '"19090:8080"' in compose
    assert '"19091:8081"' in compose
    assert "edge-control-plane-data:/var/lib/nanofaas" in compose
    assert "cloud-control-plane-data:/var/lib/nanofaas" in compose
    assert "NANOFAAS_OFFLOAD_TARGETURL: http://cloud-control-plane:8080" in compose
    assert "NANOFAAS_CONTAINER_LOCAL_CALLBACK_URL: http://cloud-control-plane:8080/v1/internal/executions" in compose
    assert "NANOFAAS_CONTAINER_LOCAL_NETWORK_NAME: nanofaas-offload-loadtest" in compose
    assert "/var/run/docker.sock:/var/run/docker.sock" in compose
