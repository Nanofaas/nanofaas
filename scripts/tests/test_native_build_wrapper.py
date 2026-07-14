from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "native-build.sh"


def test_native_build_uses_control_plane_wrapper() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert ":control-plane:nativeCompile" in script
    assert ":services:java:warm-echo:nativeCompile" in script
    assert ":nanofaas-cli:nativeCompile" in script
    assert ":nanofaas-cli:nativeSmoke" in script
    assert "services/java/warm-echo/build/native/nativeCompile/warm-echo" in script
    assert ":function-runtime:nativeCompile" not in script


def test_native_cli_smoke_is_wired_to_the_native_binary() -> None:
    build = (REPO_ROOT / "clients" / "cli" / "build.gradle").read_text(encoding="utf-8")
    smoke = REPO_ROOT / "scripts" / "native-cli-smoke.py"
    reflection = (
        REPO_ROOT
        / "clients"
        / "cli"
        / "src"
        / "main"
        / "resources"
        / "META-INF"
        / "native-image"
        / "nanofaas"
        / "nanofaas-cli"
        / "reflect-config.json"
    )

    assert "nativeSmoke" in build
    assert "nativeCompile" in build
    assert smoke.is_file()
    assert "FunctionDetails" in reflection.read_text(encoding="utf-8")
