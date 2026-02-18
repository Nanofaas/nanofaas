from __future__ import annotations

from dataclasses import dataclass
import re


WORKLOAD_ORDER = ["word-stats", "json-transform"]
RUNTIME_ORDER = ["java", "java-lite", "python", "exec"]

_PRESET_STAGE_SEQUENCES = {
    "quick": "5s:3,15s:8,15s:12,5s:0",
    "standard": "10s:5,30s:10,30s:20,30s:20,10s:0",
    "stress": "20s:10,60s:20,60s:35,60s:35,20s:0",
}

_PRESET_STAGE_TARGETS = {
    "quick": [3, 8, 12, 0],
    "standard": [5, 10, 20, 20, 0],
    "stress": [10, 20, 35, 35, 0],
}

_PAYLOAD_MODES = {"legacy-random", "pool-sequential", "pool-random"}


def _normalize_csv(values: list[str]) -> list[str]:
    normalized: list[str] = []
    seen: set[str] = set()
    for raw in values:
        item = raw.strip().lower()
        if not item or item in seen:
            continue
        seen.add(item)
        normalized.append(item)
    return normalized


def normalize_tag_suffix(value: str) -> str:
    suffix = value.strip().lower()
    if suffix in {"", "none"}:
        return ""
    if suffix.startswith("-"):
        suffix = suffix[1:]
    if not suffix:
        return ""
    return suffix


def pick_latest_base_tag(tags: list[str], fallback: str) -> str:
    semver_re = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
    best: tuple[int, int, int] | None = None
    best_tag = ""

    for raw in tags:
        tag = raw.strip()
        match = semver_re.match(tag)
        if not match:
            continue
        version = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        if best is None or version > best:
            best = version
            best_tag = tag if tag.startswith("v") else f"v{tag}"

    if best is None:
        return fallback
    return best_tag


def build_test_matrix(workloads: list[str], runtimes: list[str]) -> list[str]:
    selected_workloads = _normalize_csv(workloads)
    selected_runtimes = _normalize_csv(runtimes)

    unknown_workloads = [w for w in selected_workloads if w not in WORKLOAD_ORDER]
    if unknown_workloads:
        raise ValueError(f"unknown workloads: {', '.join(unknown_workloads)}")
    unknown_runtimes = [r for r in selected_runtimes if r not in RUNTIME_ORDER]
    if unknown_runtimes:
        raise ValueError(f"unknown runtimes: {', '.join(unknown_runtimes)}")

    ordered_workloads = [w for w in WORKLOAD_ORDER if w in selected_workloads]
    ordered_runtimes = [r for r in RUNTIME_ORDER if r in selected_runtimes]
    return [f"{workload}-{runtime}" for workload in ordered_workloads for runtime in ordered_runtimes]


def resolve_invocation_modes(value: str) -> list[str]:
    mode = value.strip().lower()
    if mode == "sync":
        return ["sync"]
    if mode == "async":
        return ["async"]
    if mode == "both":
        return ["sync", "async"]
    raise ValueError(f"invalid invocation mode: {value}")


def _build_custom_sequence(custom_total_seconds: int) -> str:
    if custom_total_seconds < 30:
        raise ValueError("custom_total_seconds must be >= 30")
    ramp_up = max(5, custom_total_seconds * 10 // 100)
    steady_1 = max(10, custom_total_seconds * 30 // 100)
    steady_2 = max(10, custom_total_seconds * 40 // 100)
    sustain = max(5, custom_total_seconds * 15 // 100)
    ramp_down = max(5, custom_total_seconds - (ramp_up + steady_1 + steady_2 + sustain))
    return f"{ramp_up}s:5,{steady_1}s:10,{steady_2}s:20,{sustain}s:20,{ramp_down}s:0"


def _validate_max_vus(max_vus: int | None) -> int | None:
    if max_vus is None:
        return None
    if max_vus < 1:
        raise ValueError(f"max_vus must be >= 1: {max_vus}")
    return max_vus


def _scale_targets(base_targets: list[int], peak: int) -> list[int]:
    base_peak = max(base_targets) if base_targets else 1
    scaled = []
    for target in base_targets:
        if target <= 0:
            scaled.append(0)
            continue
        scaled.append(max(1, round((target / base_peak) * peak)))
    return scaled


def _build_custom_sequence_with_peak(custom_total_seconds: int, peak_vus: int) -> str:
    if custom_total_seconds < 30:
        raise ValueError("custom_total_seconds must be >= 30")
    ramp_up = max(5, custom_total_seconds * 10 // 100)
    steady_1 = max(10, custom_total_seconds * 30 // 100)
    steady_2 = max(10, custom_total_seconds * 40 // 100)
    sustain = max(5, custom_total_seconds * 15 // 100)
    ramp_down = max(5, custom_total_seconds - (ramp_up + steady_1 + steady_2 + sustain))
    t1 = max(1, round(peak_vus * 0.25))
    t2 = max(1, round(peak_vus * 0.5))
    return f"{ramp_up}s:{t1},{steady_1}s:{t2},{steady_2}s:{peak_vus},{sustain}s:{peak_vus},{ramp_down}s:0"


def build_stage_sequence(
    profile: str,
    custom_total_seconds: int | None = None,
    max_vus: int | None = None,
) -> str:
    normalized_profile = profile.strip().lower()
    validated_peak = _validate_max_vus(max_vus)
    if normalized_profile == "custom":
        if custom_total_seconds is None:
            raise ValueError("custom_total_seconds is required for custom profile")
        if validated_peak is not None:
            return _build_custom_sequence_with_peak(custom_total_seconds, validated_peak)
        return _build_custom_sequence(custom_total_seconds)
    if normalized_profile not in _PRESET_STAGE_TARGETS:
        raise ValueError(f"invalid profile: {profile}")
    if validated_peak is None:
        return _PRESET_STAGE_SEQUENCES[normalized_profile]
    base_targets = _PRESET_STAGE_TARGETS[normalized_profile]
    scaled = _scale_targets(base_targets, validated_peak)
    durations = [segment.split(":")[0] for segment in _PRESET_STAGE_SEQUENCES[normalized_profile].split(",")]
    parts = [f"{dur}:{target}" for dur, target in zip(durations, scaled, strict=True)]
    return ",".join(parts)


def resolve_payload_mode(value: str) -> str:
    mode = value.strip().lower()
    if mode not in _PAYLOAD_MODES:
        raise ValueError(f"invalid payload mode: {value}")
    return mode


def resolve_payload_pool_size(value: int | str) -> int:
    try:
        size = int(str(value).strip())
    except (TypeError, ValueError) as exc:
        raise ValueError(f"payload pool size must be a positive integer: {value}") from exc
    if size < 1:
        raise ValueError(f"payload pool size must be >= 1: {size}")
    return size


@dataclass(frozen=True)
class InteractiveLoadtestConfig:
    workloads: list[str]
    runtimes: list[str]
    invocation_mode: str
    stage_profile: str
    custom_total_seconds: int | None = None
    max_vus: int | None = None
    payload_mode: str = "legacy-random"
    payload_pool_size: int = 5000

    def selected_tests(self) -> list[str]:
        return build_test_matrix(self.workloads, self.runtimes)

    def selected_modes(self) -> list[str]:
        return resolve_invocation_modes(self.invocation_mode)

    def stage_sequence(self) -> str:
        return build_stage_sequence(self.stage_profile, self.custom_total_seconds, self.max_vus)

    def payload_env(self) -> dict[str, str]:
        return {
            "K6_PAYLOAD_MODE": resolve_payload_mode(self.payload_mode),
            "K6_PAYLOAD_POOL_SIZE": str(resolve_payload_pool_size(self.payload_pool_size)),
        }
