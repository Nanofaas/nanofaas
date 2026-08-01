from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "native-build.sh"


def test_native_build_uses_control_plane_wrapper() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    properties = (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8")
    root_build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
    assert ":control-plane:nativeCompile" in script
    assert ":sdks:java:nativeTestCompile" in script
    assert ":services:java:warm-echo:nativeCompile" in script
    assert ":nanofaas-cli:nativeCompile" in script
    for function in (
        "word-stats",
        "json-transform",
        "roman-numeral",
        "figlet",
        "word-stats-lite",
        "json-transform-lite",
        "roman-numeral-lite",
    ):
        assert f":functions:java:{function}:nativeCompile" in script
    assert ":nanofaas-cli:nativeSmoke" in script
    assert "services/java/warm-echo/build/native/nativeCompile/warm-echo" in script
    assert ":function-runtime:nativeCompile" not in script
    assert "graalvmVersion=25.2.4" in properties
    assert "graalvmJavaVersion=25.0.4" in properties
    assert "graalvmVersion" in script
    assert "binaries.configureEach" in root_build
    assert "buildArgs.add('-Os')" in root_build

    sdk_build = (REPO_ROOT / "sdks" / "java" / "build.gradle").read_text(encoding="utf-8")
    warm_echo_build = (
        REPO_ROOT / "services" / "java" / "warm-echo" / "build.gradle"
    ).read_text(encoding="utf-8")
    cli_build = (REPO_ROOT / "clients" / "cli" / "build.gradle").read_text(encoding="utf-8")

    assert "org.graalvm.buildtools.native" in sdk_build
    assert "buildArgs.add('-Os')" not in sdk_build
    assert "buildArgs.add('-Os')" not in warm_echo_build
    assert "buildArgs.add('-Os')" not in cli_build


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
