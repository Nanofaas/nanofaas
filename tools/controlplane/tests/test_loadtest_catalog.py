from controlplane_tool.loadtest_catalog import list_load_profiles, resolve_load_profile


def test_load_profile_catalog_exposes_quick_smoke_stress() -> None:
    names = [profile.name for profile in list_load_profiles()]
    assert names == ["quick", "smoke", "stress"]


def test_resolve_load_profile_returns_staged_k6_shape() -> None:
    profile = resolve_load_profile("quick")

    assert profile.stages
    assert profile.summary_window_seconds > 0
