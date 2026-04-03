from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from urllib.parse import urlparse


@dataclass
class MockK8sState:
    deployments: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    services: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    pods: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
    hpas: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)


class MockK8sApiServer(ThreadingHTTPServer):
    def __init__(self, host: str, port: int) -> None:
        super().__init__((host, port), MockK8sRequestHandler)
        self.state = MockK8sState()


class MockK8sRequestHandler(BaseHTTPRequestHandler):
    server: MockK8sApiServer

    def do_GET(self) -> None:  # noqa: N802
        path = self._path()
        if path == "/healthz":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
            return
        if path == "/version":
            self._send_json(
                HTTPStatus.OK,
                {"major": "1", "minor": "29", "gitVersion": "v1.29.0-mock"},
            )
            return
        if path == "/api":
            self._send_json(
                HTTPStatus.OK,
                {
                    "kind": "APIVersions",
                    "versions": ["v1"],
                    "serverAddressByClientCIDRs": [],
                },
            )
            return
        if path == "/apis":
            self._send_json(
                HTTPStatus.OK,
                {
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
                            "preferredVersion": {
                                "groupVersion": "autoscaling/v2",
                                "version": "v2",
                            },
                        },
                    ],
                },
            )
            return
        if path == "/api/v1":
            self._send_json(
                HTTPStatus.OK,
                {
                    "kind": "APIResourceList",
                    "groupVersion": "v1",
                    "resources": [
                        {"name": "services", "singularName": "", "namespaced": True, "kind": "Service"},
                        {"name": "pods", "singularName": "", "namespaced": True, "kind": "Pod"},
                    ],
                },
            )
            return
        if path == "/apis/apps/v1":
            self._send_json(
                HTTPStatus.OK,
                {
                    "kind": "APIResourceList",
                    "groupVersion": "apps/v1",
                    "resources": [
                        {
                            "name": "deployments",
                            "singularName": "",
                            "namespaced": True,
                            "kind": "Deployment",
                        },
                        {
                            "name": "deployments/scale",
                            "singularName": "",
                            "namespaced": True,
                            "kind": "Scale",
                        },
                    ],
                },
            )
            return
        if path == "/apis/autoscaling/v2":
            self._send_json(
                HTTPStatus.OK,
                {
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
                },
            )
            return
        match = self._resource_match(path)
        if match is None:
            self._send_not_found()
            return
        namespace, kind, name, subresource = match
        if subresource is not None:
            self._send_not_found()
            return
        store = self._store_for_kind(kind)
        if name is None:
            items = [obj for (ns, _), obj in store.items() if ns == namespace]
            self._send_json(HTTPStatus.OK, {"kind": "List", "items": items})
            return
        obj = store.get((namespace, name))
        if obj is None:
            self._send_not_found()
            return
        self._send_json(HTTPStatus.OK, obj)

    def do_POST(self) -> None:  # noqa: N802
        path = self._path()
        match = self._resource_match(path)
        if match is None:
            self._send_not_found()
            return
        namespace, kind, name, subresource = match
        if name is not None or subresource is not None:
            self._send_not_found()
            return
        body = self._read_json()
        if body is None:
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "invalid JSON payload"})
            return
        metadata = body.get("metadata", {})
        if not isinstance(metadata, dict):
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "missing metadata"})
            return
        resource_name = metadata.get("name")
        if not isinstance(resource_name, str) or not resource_name.strip():
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "missing metadata.name"})
            return
        metadata.setdefault("namespace", namespace)
        body["metadata"] = metadata
        store = self._store_for_kind(kind)
        if (namespace, resource_name) in store:
            self._send_json(
                HTTPStatus.CONFLICT,
                {
                    "kind": "Status",
                    "status": "Failure",
                    "reason": "AlreadyExists",
                    "details": {"name": resource_name},
                },
            )
            return
        if kind == "pods":
            # Keep image-validator flow deterministic: pod is immediately pull-ready.
            body.setdefault(
                "status",
                {
                    "phase": "Running",
                    "containerStatuses": [{"state": {"running": {}}}],
                },
            )
        store[(namespace, resource_name)] = body
        self._send_json(HTTPStatus.CREATED, body)

    def do_DELETE(self) -> None:  # noqa: N802
        path = self._path()
        match = self._resource_match(path)
        if match is None:
            self._send_not_found()
            return
        namespace, kind, name, subresource = match
        if name is None or subresource is not None:
            self._send_not_found()
            return
        self._store_for_kind(kind).pop((namespace, name), None)
        self._send_json(
            HTTPStatus.OK,
            {"kind": "Status", "status": "Success", "details": {"name": name}},
        )

    def do_PATCH(self) -> None:  # noqa: N802
        self._handle_scale_update()

    def do_PUT(self) -> None:  # noqa: N802
        self._handle_scale_update()

    def _handle_scale_update(self) -> None:
        path = self._path()
        match = self._resource_match(path)
        if match is None:
            self._send_not_found()
            return
        namespace, kind, name, subresource = match
        if kind != "deployments" or name is None or subresource != "scale":
            self._send_not_found()
            return
        deployment = self.server.state.deployments.get((namespace, name))
        if deployment is None:
            self._send_not_found()
            return
        body = self._read_json()
        if body is None:
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "invalid JSON payload"})
            return
        replicas = self._extract_replicas(body)
        if replicas is None:
            self._send_json(HTTPStatus.BAD_REQUEST, {"message": "missing spec.replicas"})
            return
        spec = deployment.setdefault("spec", {})
        if isinstance(spec, dict):
            spec["replicas"] = replicas
        deployment["spec"] = spec
        self.server.state.deployments[(namespace, name)] = deployment
        self._send_json(
            HTTPStatus.OK,
            {
                "kind": "Scale",
                "apiVersion": "autoscaling/v1",
                "metadata": {"name": name, "namespace": namespace},
                "spec": {"replicas": replicas},
                "status": {"replicas": replicas},
            },
        )

    def _extract_replicas(self, payload: dict[str, object]) -> int | None:
        spec = payload.get("spec")
        if not isinstance(spec, dict):
            return None
        replicas = spec.get("replicas")
        if isinstance(replicas, int):
            return replicas
        return None

    def _read_json(self) -> dict[str, object] | None:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length) if content_length > 0 else b"{}"
        try:
            parsed = json.loads(raw_body.decode("utf-8"))
        except json.JSONDecodeError:
            return None
        if isinstance(parsed, dict):
            return parsed
        return None

    def _resource_match(
        self,
        path: str,
    ) -> tuple[str, str, str | None, str | None] | None:
        parts = [part for part in path.strip("/").split("/") if part]

        if len(parts) >= 4 and parts[:3] == ["apis", "apps", "v1"] and parts[3] == "namespaces":
            if len(parts) < 6:
                return None
            namespace = parts[4]
            if parts[5] != "deployments":
                return None
            name = parts[6] if len(parts) >= 7 else None
            subresource = parts[7] if len(parts) >= 8 else None
            return (namespace, "deployments", name, subresource)

        if parts[:3] == ["api", "v1", "namespaces"]:
            if len(parts) < 5:
                return None
            namespace = parts[3]
            if parts[4] not in {"services", "pods"}:
                return None
            kind = parts[4]
            name = parts[5] if len(parts) >= 6 else None
            subresource = parts[6] if len(parts) >= 7 else None
            return (namespace, kind, name, subresource)

        if (
            len(parts) >= 4
            and parts[:3] == ["apis", "autoscaling", "v2"]
            and parts[3] == "namespaces"
        ):
            if len(parts) < 6:
                return None
            namespace = parts[4]
            if parts[5] != "horizontalpodautoscalers":
                return None
            name = parts[6] if len(parts) >= 7 else None
            subresource = parts[7] if len(parts) >= 8 else None
            return (namespace, "horizontalpodautoscalers", name, subresource)
        return None

    def _store_for_kind(self, kind: str) -> dict[tuple[str, str], dict[str, object]]:
        if kind == "deployments":
            return self.server.state.deployments
        if kind == "services":
            return self.server.state.services
        if kind == "pods":
            return self.server.state.pods
        if kind == "horizontalpodautoscalers":
            return self.server.state.hpas
        raise ValueError(f"unsupported resource kind: {kind}")

    def _path(self) -> str:
        return urlparse(self.path).path

    def _send_not_found(self) -> None:
        self._send_json(
            HTTPStatus.NOT_FOUND,
            {"kind": "Status", "status": "Failure", "reason": "NotFound"},
        )

    def _send_json(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local mock Kubernetes API server.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()

    server = MockK8sApiServer(host=args.host, port=args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
