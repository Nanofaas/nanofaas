from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_java_container_images_target_java_25():
    # These JVM Dockerfiles jlink a minimal runtime (only the modules the jar actually uses)
    # from eclipse-temurin:25-jdk, and run it on the same distroless/base image the native
    # builds use — not the full stock JRE in gcr.io/distroless/java25-debian13.
    jlink_dockerfiles = [
        "deploy/compose/Dockerfile",
        "platform/control-plane/Dockerfile",
        "services/java/warm-echo/Dockerfile",
        "runtimes/watchdog/Dockerfile.combined",
        "functions/java/word-stats/Dockerfile",
        "functions/java/json-transform/Dockerfile",
        "functions/java/roman-numeral/Dockerfile",
        "functions/java/figlet/Dockerfile",
        "tools/fn-init/src/fn_init/templates/java/Dockerfile.tmpl",
    ]
    for relative_path in jlink_dockerfiles:
        dockerfile = (REPO_ROOT / relative_path).read_text()
        assert "eclipse-temurin:25-jdk" in dockerfile
        assert "jlink" in dockerfile
        assert "gcr.io/distroless/base-debian13:nonroot" in dockerfile
        assert "gcr.io/distroless/java25-debian13:nonroot" not in dockerfile

    for relative_path in (
        "deploy/compose/Dockerfile",
        "platform/control-plane/Dockerfile",
    ):
        control_plane_dockerfile = (REPO_ROOT / relative_path).read_text()
        assert "jdk.httpserver" in control_plane_dockerfile

    native_dockerfiles = [
        "functions/java/word-stats-lite/Dockerfile",
        "functions/java/json-transform-lite/Dockerfile",
        "functions/java/roman-numeral-lite/Dockerfile",
    ]
    for relative_path in native_dockerfiles:
        dockerfile = (REPO_ROOT / relative_path).read_text()
        assert "scripts/install-graalvm.sh" in dockerfile
        # `base` and not `cc` here, unlike the shared builder: these are compiled
        # with the serial collector, which is plain C and loads no libstdc++.
        assert "gcr.io/distroless/base-debian13:nonroot" in dockerfile

    native_build_files = [
        "platform/control-plane/build.gradle",
        "services/java/warm-echo/build.gradle",
        "functions/java/word-stats/build.gradle",
        "functions/java/json-transform/build.gradle",
        "functions/java/roman-numeral/build.gradle",
        "functions/java/figlet/build.gradle",
        "tools/fn-init/src/fn_init/templates/java/build.gradle.tmpl",
    ]
    for relative_path in native_build_files:
        build_file = (REPO_ROOT / relative_path).read_text()
        assert "'BP_NATIVE_IMAGE': 'true'" not in build_file


def test_native_java_images_use_the_shared_builder():
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text()
    wrapper = (REPO_ROOT / "scripts/native-java-image.sh").read_text()

    assert "scripts/install-graalvm.sh" in dockerfile
    assert "graalvmVersion" in dockerfile
    assert "ARG NATIVE_TASK" in dockerfile
    assert "ARG NATIVE_BINARY" in dockerfile
    # `cc`, not `base`: a G1 binary loads libstdc++ at run time because the
    # collector is C++. On `base` it started and died with
    # "libstdc++.so.6: cannot open shared object file".
    assert "gcr.io/distroless/cc-debian13:nonroot" in dockerfile

    for target in (
        "control-plane",
        "warm-echo",
        "word-stats",
        "json-transform",
        "roman-numeral",
        "figlet",
        "word-stats-lite",
        "json-transform-lite",
        "roman-numeral-lite",
    ):
        assert f"{target})" in wrapper


def test_native_builder_can_link_the_g1_collector():
    """G1's collector library is C++; the serial one is not.

    `libstdc++-devel` is the obvious package and the wrong one: on Oracle Linux 9
    it installs cleanly and ships only `libstdc++fs.a`, so `-lstdc++` still has
    nothing to resolve against and the build dies at the link step — after eleven
    minutes of compiling, which is an expensive way to learn it. `gcc-c++` brings
    /usr/lib/gcc/x86_64-redhat-linux/11/libstdc++.so, which is what works.
    """
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text(encoding="utf-8")
    install = next(
        line for line in dockerfile.splitlines() if "microdnf install" in line
    )

    assert "gcc-c++" in install, "a G1 build cannot link without a C++ toolchain"
