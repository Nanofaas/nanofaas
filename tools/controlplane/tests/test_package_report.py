from __future__ import annotations

from controlplane_tool.devtools.package_report import (
    PackageMetrics,
    calculate_metrics,
    format_metrics_table,
)


def test_calculate_metrics_counts_internal_outgoing_and_incoming_edges() -> None:
    metrics = calculate_metrics(
        packages=[
            "controlplane_tool.core",
            "controlplane_tool.loadtest",
            "controlplane_tool.tui",
        ],
        edges=[
            ("controlplane_tool.core.models", "controlplane_tool.core.net_utils"),
            ("controlplane_tool.loadtest.k6_ops", "controlplane_tool.core.models"),
            ("controlplane_tool.tui.app", "controlplane_tool.loadtest.loadtest_models"),
        ],
    )

    by_package = {metric.package: metric for metric in metrics}

    assert by_package["controlplane_tool.core"] == PackageMetrics(
        package="controlplane_tool.core",
        internal_imports=1,
        outgoing_imports=0,
        incoming_imports=1,
        instability=0.0,
    )
    assert by_package["controlplane_tool.loadtest"] == PackageMetrics(
        package="controlplane_tool.loadtest",
        internal_imports=0,
        outgoing_imports=1,
        incoming_imports=1,
        instability=0.5,
    )
    assert by_package["controlplane_tool.tui"] == PackageMetrics(
        package="controlplane_tool.tui",
        internal_imports=0,
        outgoing_imports=1,
        incoming_imports=0,
        instability=1.0,
    )


def test_format_metrics_table_includes_header_and_package_rows() -> None:
    table = format_metrics_table(
        [
            PackageMetrics(
                package="controlplane_tool.core",
                internal_imports=1,
                outgoing_imports=0,
                incoming_imports=2,
                instability=0.0,
            )
        ]
    )

    assert "package" in table
    assert "internal" in table
    assert "outgoing" in table
    assert "incoming" in table
    assert "instability" in table
    assert "controlplane_tool.core" in table
