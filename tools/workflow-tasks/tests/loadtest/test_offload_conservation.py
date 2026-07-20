from workflow_tasks.loadtest.offload_conservation import evaluate_conservation

OFFLOADABLE = "word-stats-java"
CONTROL = "json-transform-java"


def _edge_metrics(
    *,
    success_offloadable: float = 1198,
    depth: float = 400,
    est_wait: float = 200,
    control_offload: float = 0,
    retry_offloadable: float = 0,
    retry_control: float = 0,
    with_failure_meter: bool = False,
) -> str:
    lines = [
        f'function_success_total{{function="{OFFLOADABLE}"}} {success_offloadable}',
        f'nanofaas_offload_total{{function="{OFFLOADABLE}",trigger="depth"}} {depth}',
        f'nanofaas_offload_total{{function="{OFFLOADABLE}",trigger="est_wait"}} {est_wait}',
        f'function_retry_total{{function="{OFFLOADABLE}"}} {retry_offloadable}',
        f'function_retry_total{{function="{CONTROL}"}} {retry_control}',
    ]
    if control_offload:
        lines.append(f'nanofaas_offload_total{{function="{CONTROL}",trigger="depth"}} {control_offload}')
    if with_failure_meter:
        lines.append(f'nanofaas_offload_failure_total{{function="{OFFLOADABLE}"}} 3.0')
    return "\n".join(lines) + "\n"


def _cloud_metrics(*, success_offloadable: float = 598, leak_control: bool = False) -> str:
    lines = [f'function_success_total{{function="{OFFLOADABLE}"}} {success_offloadable}']
    if leak_control:
        lines.append(f'function_success_total{{function="{CONTROL}"}} 5.0')
    return "\n".join(lines) + "\n"


def _k6_summary(*, offloadable_requests: float = 1200, offloaded: float = 600) -> dict:
    # k6's real --summary-export shape: counter fields are flat (no "values"
    # wrapper), and it only emits a per-tag submetric key for tag combinations
    # referenced by a threshold — neither of these counters qualifies, so both
    # only ever appear untagged.
    return {
        "metrics": {
            "offloadable_requests": {"count": offloadable_requests},
            "offloaded_requests": {"count": offloaded},
        }
    }


def test_conservation_holds_within_tolerance() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(),
        cloud_metrics=_cloud_metrics(),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is True
    assert report.failures == ()
    assert report.numbers["k6_offloaded_requests"] == 600
    assert report.numbers["edge_offload_total"] == 600


def test_offload_count_mismatch_beyond_tolerance_fails() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(),
        cloud_metrics=_cloud_metrics(success_offloadable=550),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("cloud function_success_total for offloadable" in failure for failure in report.failures)


def test_control_function_leaking_to_cloud_fails() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(),
        cloud_metrics=_cloud_metrics(leak_control=True),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("cloud metrics mention the control function" in failure for failure in report.failures)


def test_zero_offloaded_requests_fails_even_if_otherwise_balanced() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(offloaded=0),
        edge_metrics=_edge_metrics(depth=0, est_wait=0),
        cloud_metrics=_cloud_metrics(success_offloadable=0),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("no requests were offloaded" in failure for failure in report.failures)


def test_control_offloaded_on_edge_fails() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(control_offload=12),
        cloud_metrics=_cloud_metrics(),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("control function" in failure and "expected 0" in failure for failure in report.failures)


def test_offload_failure_meter_on_edge_fails() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(with_failure_meter=True),
        cloud_metrics=_cloud_metrics(),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("nanofaas_offload_failure_total" in failure for failure in report.failures)


def test_retries_on_edge_fail() -> None:
    report = evaluate_conservation(
        k6_summary=_k6_summary(),
        edge_metrics=_edge_metrics(retry_offloadable=9),
        cloud_metrics=_cloud_metrics(),
        offloadable=OFFLOADABLE,
        control=CONTROL,
    )

    assert report.passed is False
    assert any("function_retry_total" in failure for failure in report.failures)
