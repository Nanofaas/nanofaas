from __future__ import annotations

import argparse
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

import grimp


ROOT_PACKAGE = "controlplane_tool"
TOP_LEVEL_PACKAGES = (
    "controlplane_tool.app",
    "controlplane_tool.building",
    "controlplane_tool.cli",
    "controlplane_tool.cli_validation",
    "controlplane_tool.core",
    "controlplane_tool.e2e",
    "controlplane_tool.functions",
    "controlplane_tool.infra",
    "controlplane_tool.loadtest",
    "controlplane_tool.orchestation",
    "controlplane_tool.scenario",
    "controlplane_tool.sut",
    "controlplane_tool.tui",
    "controlplane_tool.workflow",
)


@dataclass(frozen=True)
class PackageMetrics:
    package: str
    internal_imports: int
    outgoing_imports: int
    incoming_imports: int
    instability: float


def _top_level_package(module: str, packages: Sequence[str]) -> str | None:
    for package in packages:
        if module == package or module.startswith(f"{package}."):
            return package
    return None


def calculate_metrics(
    *,
    packages: Sequence[str],
    edges: Iterable[tuple[str, str]],
) -> list[PackageMetrics]:
    internal_counts = {package: 0 for package in packages}
    outgoing_counts = {package: 0 for package in packages}
    incoming_counts = {package: 0 for package in packages}

    for importer, imported in edges:
        importer_package = _top_level_package(importer, packages)
        imported_package = _top_level_package(imported, packages)
        if importer_package is None or imported_package is None:
            continue
        if importer_package == imported_package:
            internal_counts[importer_package] += 1
            continue
        outgoing_counts[importer_package] += 1
        incoming_counts[imported_package] += 1

    metrics: list[PackageMetrics] = []
    for package in packages:
        outgoing = outgoing_counts[package]
        incoming = incoming_counts[package]
        denominator = incoming + outgoing
        instability = round(outgoing / denominator, 2) if denominator else 0.0
        metrics.append(
            PackageMetrics(
                package=package,
                internal_imports=internal_counts[package],
                outgoing_imports=outgoing,
                incoming_imports=incoming,
                instability=instability,
            )
        )
    return metrics


def format_metrics_table(metrics: Sequence[PackageMetrics]) -> str:
    header = f"{'package':38} {'internal':>8} {'outgoing':>8} {'incoming':>8} {'instability':>11}"
    rows = [header, "-" * len(header)]
    for metric in metrics:
        rows.append(
            f"{metric.package:38} "
            f"{metric.internal_imports:8d} "
            f"{metric.outgoing_imports:8d} "
            f"{metric.incoming_imports:8d} "
            f"{metric.instability:11.2f}"
        )
    return "\n".join(rows)


def _iter_grimp_edges(root_package: str) -> list[tuple[str, str]]:
    graph = grimp.build_graph(root_package, include_external_packages=False)
    modules = sorted(
        module
        for module in graph.modules
        if module == root_package or module.startswith(f"{root_package}.")
    )
    edges: list[tuple[str, str]] = []
    for importer in modules:
        for imported in sorted(graph.find_modules_directly_imported_by(importer)):
            if imported == root_package or imported.startswith(f"{root_package}."):
                edges.append((importer, imported))
    return edges


def build_current_metrics() -> list[PackageMetrics]:
    return calculate_metrics(
        packages=TOP_LEVEL_PACKAGES,
        edges=_iter_grimp_edges(ROOT_PACKAGE),
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Report internal and cross-package imports for controlplane_tool."
    )
    parser.parse_args()
    print(format_metrics_table(build_current_metrics()))


if __name__ == "__main__":
    main()
