from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def _line_index(lines, needle, start=0):
    return next(i for i, line in enumerate(lines) if i >= start and needle in line)


def _assert_runtime_stage_redeclares_and_reports_base_images(dockerfile_text):
    # Substring-only checks can't tell a real redeclaration from a stray earlier
    # match, and the ARG must come strictly between the runtime FROM and the ENV
    # lines that consume it: a global ARG (declared before the first FROM) does
    # NOT carry into a later stage's non-FROM instructions, so a redeclaration
    # placed anywhere else leaves NANOFAAS_BUILD_BASE_IMAGE / _RUNTIME_BASE_IMAGE
    # resolving to empty at runtime.
    lines = dockerfile_text.splitlines()

    runtime_from = _line_index(lines, "FROM ${RUNTIME_IMAGE}")
    # search strictly after the runtime FROM: the global declarations above the
    # first FROM also contain these substrings, and matching those would let a
    # missing redeclaration slip through undetected.
    arg_builder = _line_index(lines, "ARG BUILDER_IMAGE", start=runtime_from + 1)
    arg_runtime = _line_index(lines, "ARG RUNTIME_IMAGE", start=runtime_from + 1)
    env_builder = _line_index(lines, "ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}", start=runtime_from + 1)
    env_runtime = _line_index(lines, "ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}", start=runtime_from + 1)

    # both redeclarations are bare (no default) - they must resolve from the
    # stage's build-args, not silently pick up a hardcoded value here.
    assert lines[arg_builder].strip() == "ARG BUILDER_IMAGE"
    assert lines[arg_runtime].strip() == "ARG RUNTIME_IMAGE"

    assert runtime_from < arg_builder < env_builder
    assert runtime_from < arg_runtime < env_runtime


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


def test_jvm_dockerfiles_report_their_base_images():
    # /modules/build-metadata reads NANOFAAS_BUILD_BASE_IMAGE and
    # NANOFAAS_RUNTIME_BASE_IMAGE from the running process's environment, so the
    # image actually built FROM must be the value reported — not a hardcoded
    # string that can drift from the FROM line.
    for relative_path, builder_image, runtime_image in (
        ("platform/control-plane/Dockerfile", "eclipse-temurin:25-jdk", "gcr.io/distroless/base-debian13:nonroot"),
        ("deploy/compose/Dockerfile", "eclipse-temurin:25-jdk", "gcr.io/distroless/base-debian13:nonroot"),
    ):
        dockerfile = (REPO_ROOT / relative_path).read_text()
        assert f"ARG BUILDER_IMAGE={builder_image}" in dockerfile
        assert f"ARG RUNTIME_IMAGE={runtime_image}" in dockerfile
        assert "FROM ${BUILDER_IMAGE}" in dockerfile
        assert "FROM ${RUNTIME_IMAGE}" in dockerfile
        assert "ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}" in dockerfile
        assert "ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}" in dockerfile
        _assert_runtime_stage_redeclares_and_reports_base_images(dockerfile)


def test_native_java_dockerfile_reports_its_base_images():
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text()

    assert "ARG BUILDER_IMAGE=oraclelinux:9-slim" in dockerfile
    assert "ARG RUNTIME_IMAGE=gcr.io/distroless/cc-debian13:nonroot" in dockerfile
    assert "FROM ${BUILDER_IMAGE}" in dockerfile
    assert "FROM ${RUNTIME_IMAGE}" in dockerfile
    assert "ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}" in dockerfile
    assert "ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}" in dockerfile
    _assert_runtime_stage_redeclares_and_reports_base_images(dockerfile)


def test_native_java_image_script_passes_build_identity_properties():
    wrapper = (REPO_ROOT / "scripts/native-java-image.sh").read_text()

    assert '-PnanofaasBuildType=native' in wrapper
    assert 'NANOFAAS_BUILD_VARIANT' in wrapper
    assert '-PnanofaasBuildVariant=' in wrapper
    assert '-PnanofaasBuildOptimization=' in wrapper


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
