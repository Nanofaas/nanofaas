from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from loadtest_registry_config import (
    build_stage_sequence,
    build_test_matrix,
    normalize_tag_suffix,
    pick_latest_base_tag,
    resolve_payload_mode,
    resolve_payload_pool_size,
    resolve_invocation_modes,
)


def test_build_test_matrix_respects_workload_and_runtime_order():
    tests = build_test_matrix(
        workloads=["word-stats", "json-transform"],
        runtimes=["java", "python"],
    )
    assert tests == [
        "word-stats-java",
        "word-stats-python",
        "json-transform-java",
        "json-transform-python",
    ]


def test_build_test_matrix_supports_java_lite_and_exec():
    tests = build_test_matrix(
        workloads=["word-stats"],
        runtimes=["java-lite", "exec"],
    )
    assert tests == ["word-stats-java-lite", "word-stats-exec"]


def test_resolve_invocation_modes_accepts_both():
    assert resolve_invocation_modes("both") == ["sync", "async"]


def test_resolve_invocation_modes_rejects_invalid_value():
    try:
        resolve_invocation_modes("invalid")
    except ValueError as exc:
        assert "invalid invocation mode" in str(exc)
    else:
        raise AssertionError("Expected ValueError for invalid mode")


def test_build_stage_sequence_supports_presets_and_custom_seconds():
    assert build_stage_sequence("standard") == "10s:5,30s:10,30s:20,30s:20,10s:0"
    assert build_stage_sequence("quick") == "5s:3,15s:8,15s:12,5s:0"
    assert build_stage_sequence("stress") == "20s:10,60s:20,60s:35,60s:35,20s:0"
    assert build_stage_sequence("custom", custom_total_seconds=120) == "12s:5,36s:10,48s:20,18s:20,6s:0"


def test_build_stage_sequence_supports_custom_peak_vus():
    assert build_stage_sequence("standard", max_vus=30) == "10s:8,30s:15,30s:30,30s:30,10s:0"
    assert build_stage_sequence("quick", max_vus=24) == "5s:6,15s:16,15s:24,5s:0"
    assert build_stage_sequence("custom", custom_total_seconds=120, max_vus=40) == "12s:10,36s:20,48s:40,18s:40,6s:0"


def test_build_stage_sequence_rejects_invalid_custom_peak_vus():
    for bad in (0, -1):
        try:
            build_stage_sequence("standard", max_vus=bad)
        except ValueError as exc:
            assert "max_vus" in str(exc)
        else:
            raise AssertionError(f"Expected ValueError for max_vus={bad}")


def test_normalize_tag_suffix_supports_arm64_and_amd64():
    assert normalize_tag_suffix("arm64") == "arm64"
    assert normalize_tag_suffix("-arm64") == "arm64"
    assert normalize_tag_suffix("amd64") == "amd64"
    assert normalize_tag_suffix("-amd64") == "amd64"


def test_normalize_tag_suffix_accepts_none_marker():
    assert normalize_tag_suffix("none") == ""
    assert normalize_tag_suffix("") == ""


def test_pick_latest_base_tag_ignores_arch_suffix_tags():
    tags = ["v0.12.0-arm64", "v0.11.9", "v0.12.0", "v0.12.1-amd64", "v0.13.0"]
    assert pick_latest_base_tag(tags, fallback="v0.12.0") == "v0.13.0"


def test_pick_latest_base_tag_falls_back_when_no_semver_tags():
    tags = ["latest", "dev", "v0.12.0-arm64"]
    assert pick_latest_base_tag(tags, fallback="v0.12.0") == "v0.12.0"


def test_resolve_payload_mode_accepts_supported_values():
    assert resolve_payload_mode("legacy-random") == "legacy-random"
    assert resolve_payload_mode("pool-sequential") == "pool-sequential"
    assert resolve_payload_mode("pool-random") == "pool-random"


def test_resolve_payload_mode_rejects_invalid_value():
    try:
        resolve_payload_mode("invalid")
    except ValueError as exc:
        assert "invalid payload mode" in str(exc)
    else:
        raise AssertionError("Expected ValueError for invalid payload mode")


def test_resolve_payload_pool_size_requires_positive_integer():
    assert resolve_payload_pool_size("5000") == 5000
    assert resolve_payload_pool_size(1) == 1
    for value in ("0", 0, "-5", -5, "abc"):
        try:
            resolve_payload_pool_size(value)
        except ValueError as exc:
            assert "payload pool size" in str(exc)
        else:
            raise AssertionError(f"Expected ValueError for payload pool size={value!r}")
