from __future__ import annotations

import argparse
from dataclasses import dataclass, field

from flask import Flask, jsonify, request, current_app


@dataclass
class MockK8sState:
    deployments: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    services: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    pods: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    hpas: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)


def _store_for_kind(state: MockK8sState, kind: str) -> dict[tuple[str, str], dict[str, object]]:
    if kind == "deployments":
        return state.deployments
    if kind == "services":
        return state.services
    if kind == "pods":
        return state.pods
    if kind == "horizontalpodautoscalers":
        return state.hpas
    raise ValueError(f"unsupported resource kind: {kind}")


def _extract_replicas(payload: dict[str, object]) -> int | None:
    spec = payload.get("spec")
    if not isinstance(spec, dict):
        return None
    replicas = spec.get("replicas")
    if isinstance(replicas, int):
        return replicas
    return None


def _not_found():
    return jsonify({"kind": "Status", "status": "Failure", "reason": "NotFound"}), 404


def _get_state() -> MockK8sState:
    return current_app.config["STATE"]


def _create_app(state: MockK8sState) -> Flask:
    app = Flask(__name__)
    app.config["STATE"] = state

    @app.get("/healthz")
    def healthz():
        return jsonify({"status": "ok"})

    @app.get("/version")
    def version():
        return jsonify({"major": "1", "minor": "29", "gitVersion": "v1.29.0-mock"})

    @app.get("/api")
    def api():
        return jsonify({"kind": "APIVersions", "versions": ["v1"], "serverAddressByClientCIDRs": []})

    @app.get("/apis")
    def apis():
        return jsonify({
            "kind": "APIGroupList",
            "groups": [
                {
                    "name": "apps",
                    "versions": [{"groupVersion": "apps/v1", "version": "v1"}],
                    "preferredVersion": {"groupVersion": "apps/v1", "version": "v1"},
                },
                {
                    "name": "autoscaling",
                    "versions": [{"groupVersion": "autoscaling/v2", "version": "v2"}],
                    "preferredVersion": {"groupVersion": "autoscaling/v2", "version": "v2"},
                },
            ],
        })

    @app.get("/api/v1")
    def api_v1():
        return jsonify({
            "kind": "APIResourceList",
            "groupVersion": "v1",
            "resources": [
                {"name": "services", "singularName": "", "namespaced": True, "kind": "Service"},
                {"name": "pods", "singularName": "", "namespaced": True, "kind": "Pod"},
            ],
        })

    @app.get("/apis/apps/v1")
    def apis_apps_v1():
        return jsonify({
            "kind": "APIResourceList",
            "groupVersion": "apps/v1",
            "resources": [
                {"name": "deployments", "singularName": "", "namespaced": True, "kind": "Deployment"},
                {"name": "deployments/scale", "singularName": "", "namespaced": True, "kind": "Scale"},
            ],
        })

    @app.get("/apis/autoscaling/v2")
    def apis_autoscaling_v2():
        return jsonify({
            "kind": "APIResourceList",
            "groupVersion": "autoscaling/v2",
            "resources": [
                {
                    "name": "horizontalpodautoscalers",
                    "singularName": "",
                    "namespaced": True,
                    "kind": "HorizontalPodAutoscaler",
                },
            ],
        })

    # ── Deployments ──────────────────────────────────────────────────────────

    @app.get("/apis/apps/v1/namespaces/<namespace>/deployments")
    def list_deployments(namespace: str):
        state = _get_state()
        items = [obj for (ns, _), obj in state.deployments.items() if ns == namespace]
        return jsonify({"kind": "List", "items": items})

    @app.post("/apis/apps/v1/namespaces/<namespace>/deployments")
    def create_deployment(namespace: str):
        return _create_resource("deployments", namespace)

    @app.get("/apis/apps/v1/namespaces/<namespace>/deployments/<name>")
    def get_deployment(namespace: str, name: str):
        return _get_resource("deployments", namespace, name)

    @app.delete("/apis/apps/v1/namespaces/<namespace>/deployments/<name>")
    def delete_deployment(namespace: str, name: str):
        return _delete_resource("deployments", namespace, name)

    @app.route(
        "/apis/apps/v1/namespaces/<namespace>/deployments/<name>/scale",
        methods=["PATCH", "PUT"],
    )
    def scale_deployment(namespace: str, name: str):
        state = _get_state()
        deployment = state.deployments.get((namespace, name))
        if deployment is None:
            return _not_found()
        body = request.get_json(silent=True)
        if body is None:
            return jsonify({"message": "invalid JSON payload"}), 400
        replicas = _extract_replicas(body)
        if replicas is None:
            return jsonify({"message": "missing spec.replicas"}), 400
        spec = deployment.setdefault("spec", {})
        if isinstance(spec, dict):
            spec["replicas"] = replicas
        deployment["spec"] = spec
        state.deployments[(namespace, name)] = deployment
        return jsonify({
            "kind": "Scale",
            "apiVersion": "autoscaling/v1",
            "metadata": {"name": name, "namespace": namespace},
            "spec": {"replicas": replicas},
            "status": {"replicas": replicas},
        })

    # ── Services ─────────────────────────────────────────────────────────────

    @app.get("/api/v1/namespaces/<namespace>/services")
    def list_services(namespace: str):
        state = _get_state()
        items = [obj for (ns, _), obj in state.services.items() if ns == namespace]
        return jsonify({"kind": "List", "items": items})

    @app.post("/api/v1/namespaces/<namespace>/services")
    def create_service(namespace: str):
        return _create_resource("services", namespace)

    @app.get("/api/v1/namespaces/<namespace>/services/<name>")
    def get_service(namespace: str, name: str):
        return _get_resource("services", namespace, name)

    @app.delete("/api/v1/namespaces/<namespace>/services/<name>")
    def delete_service(namespace: str, name: str):
        return _delete_resource("services", namespace, name)

    # ── Pods ──────────────────────────────────────────────────────────────────

    @app.get("/api/v1/namespaces/<namespace>/pods")
    def list_pods(namespace: str):
        state = _get_state()
        items = [obj for (ns, _), obj in state.pods.items() if ns == namespace]
        return jsonify({"kind": "List", "items": items})

    @app.post("/api/v1/namespaces/<namespace>/pods")
    def create_pod(namespace: str):
        return _create_resource("pods", namespace)

    @app.get("/api/v1/namespaces/<namespace>/pods/<name>")
    def get_pod(namespace: str, name: str):
        return _get_resource("pods", namespace, name)

    @app.delete("/api/v1/namespaces/<namespace>/pods/<name>")
    def delete_pod(namespace: str, name: str):
        return _delete_resource("pods", namespace, name)

    # ── HPAs ─────────────────────────────────────────────────────────────────

    @app.get("/apis/autoscaling/v2/namespaces/<namespace>/horizontalpodautoscalers")
    def list_hpas(namespace: str):
        state = _get_state()
        items = [obj for (ns, _), obj in state.hpas.items() if ns == namespace]
        return jsonify({"kind": "List", "items": items})

    @app.post("/apis/autoscaling/v2/namespaces/<namespace>/horizontalpodautoscalers")
    def create_hpa(namespace: str):
        return _create_resource("horizontalpodautoscalers", namespace)

    @app.get("/apis/autoscaling/v2/namespaces/<namespace>/horizontalpodautoscalers/<name>")
    def get_hpa(namespace: str, name: str):
        return _get_resource("horizontalpodautoscalers", namespace, name)

    @app.delete("/apis/autoscaling/v2/namespaces/<namespace>/horizontalpodautoscalers/<name>")
    def delete_hpa(namespace: str, name: str):
        return _delete_resource("horizontalpodautoscalers", namespace, name)

    # ── Error handlers ───────────────────────────────────────────────────────

    @app.errorhandler(404)
    def handle_404(e):
        return _not_found()

    @app.errorhandler(405)
    def handle_405(e):
        return _not_found()

    # ── Shared helpers ────────────────────────────────────────────────────────

    def _create_resource(kind: str, namespace: str):
        state = _get_state()
        body = request.get_json(silent=True)
        if body is None:
            return jsonify({"message": "invalid JSON payload"}), 400
        metadata = body.get("metadata", {})
        if not isinstance(metadata, dict):
            return jsonify({"message": "missing metadata"}), 400
        resource_name = metadata.get("name")
        if not isinstance(resource_name, str) or not resource_name.strip():
            return jsonify({"message": "missing metadata.name"}), 400
        metadata.setdefault("namespace", namespace)
        body["metadata"] = metadata
        store = _store_for_kind(state, kind)
        if (namespace, resource_name) in store:
            return jsonify({
                "kind": "Status",
                "status": "Failure",
                "reason": "AlreadyExists",
                "details": {"name": resource_name},
            }), 409
        if kind == "pods":
            body.setdefault(
                "status",
                {"phase": "Running", "containerStatuses": [{"state": {"running": {}}}]},
            )
        store[(namespace, resource_name)] = body
        return jsonify(body), 201

    def _get_resource(kind: str, namespace: str, name: str):
        state = _get_state()
        obj = _store_for_kind(state, kind).get((namespace, name))
        if obj is None:
            return _not_found()
        return jsonify(obj)

    def _delete_resource(kind: str, namespace: str, name: str):
        state = _get_state()
        _store_for_kind(state, kind).pop((namespace, name), None)
        return jsonify({"kind": "Status", "status": "Success", "details": {"name": name}})

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local mock Kubernetes API server.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()

    app = _create_app(MockK8sState())
    import logging
    log = logging.getLogger("werkzeug")
    log.setLevel(logging.ERROR)
    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
