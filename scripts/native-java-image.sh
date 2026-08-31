#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

target="${1:-}"
image="${2:-}"
gradle_args="-PnanofaasBuildType=native"

if [ -n "${NANOFAAS_BUILD_VARIANT:-}" ]; then
  gradle_args="$gradle_args -PnanofaasBuildVariant=$NANOFAAS_BUILD_VARIANT"
fi

# The builder stage COPYs the repo without .git (see .dockerignore) and installs no git,
# so generateBuildMetadata can't shell out to git inside the container. Resolve the real
# revision/dirty state on the host, where both are available, and inject them.
host_git_revision="$(git rev-parse HEAD 2>/dev/null || true)"
if [ -n "$host_git_revision" ]; then
  gradle_args="$gradle_args -PnanofaasBuildRevision=$host_git_revision"
  if [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]; then
    gradle_args="$gradle_args -PnanofaasBuildDirty=true"
  else
    gradle_args="$gradle_args -PnanofaasBuildDirty=false"
  fi
fi

case "$target" in
  control-plane)
    task=":control-plane:nativeCompile"
    binary="platform/control-plane/build/native/nativeCompile/control-plane"
    default_image="nanofaas/control-plane:native"
    gradle_args="$gradle_args -PcontrolPlaneModules=${CONTROL_PLANE_MODULES:-all}"
    ;;
  warm-echo)
    task=":services:java:warm-echo:nativeCompile"
    binary="services/java/warm-echo/build/native/nativeCompile/warm-echo"
    default_image="nanofaas/java-warm-echo:native"
    ;;
  word-stats)
    task=":functions:java:word-stats:nativeCompile"
    binary="functions/java/word-stats/build/native/nativeCompile/word-stats"
    default_image="nanofaas/java-word-stats:native"
    ;;
  json-transform)
    task=":functions:java:json-transform:nativeCompile"
    binary="functions/java/json-transform/build/native/nativeCompile/json-transform"
    default_image="nanofaas/java-json-transform:native"
    ;;
  roman-numeral)
    task=":functions:java:roman-numeral:nativeCompile"
    binary="functions/java/roman-numeral/build/native/nativeCompile/roman-numeral"
    default_image="nanofaas/java-roman-numeral:native"
    ;;
  figlet)
    task=":functions:java:figlet:nativeCompile"
    binary="functions/java/figlet/build/native/nativeCompile/figlet"
    default_image="nanofaas/java-figlet:native"
    ;;
  word-stats-lite)
    task=":functions:java:word-stats-lite:nativeCompile"
    binary="functions/java/word-stats-lite/build/native/nativeCompile/word-stats-lite"
    default_image="nanofaas/java-word-stats-lite:native"
    ;;
  json-transform-lite)
    task=":functions:java:json-transform-lite:nativeCompile"
    binary="functions/java/json-transform-lite/build/native/nativeCompile/json-transform-lite"
    default_image="nanofaas/java-json-transform-lite:native"
    ;;
  roman-numeral-lite)
    task=":functions:java:roman-numeral-lite:nativeCompile"
    binary="functions/java/roman-numeral-lite/build/native/nativeCompile/roman-numeral-lite"
    default_image="nanofaas/java-roman-numeral-lite:native"
    ;;
  *)
    echo "Usage: $0 {control-plane|warm-echo|word-stats|json-transform|roman-numeral|figlet|word-stats-lite|json-transform-lite|roman-numeral-lite} [image]" >&2
    exit 2
    ;;
esac

image="${image:-$default_image}"

# NATIVE_GC=G1 selects the collector, and pulls in the distribution that has one:
# Community's Native Image offers only 'serial' and 'epsilon'. Asking for G1
# without Oracle GraalVM fails at build time rather than silently falling back,
# which is the behaviour worth keeping — a run that reported "G1" while using the
# serial collector would be worse than no run at all.
graalvm_distribution="${GRAALVM_DISTRIBUTION:-community}"
if [ -n "${NATIVE_GC:-}" ]; then
  gradle_args="$gradle_args -PnativeGc=$NATIVE_GC"
  if [ "$NATIVE_GC" = "G1" ]; then
    graalvm_distribution="${GRAALVM_DISTRIBUTION:-oracle}"
  fi
fi

# NATIVE_MONITORING=jfr,jvmstat turns on the run-time inspection a native image
# otherwise lacks. Worth the binary size where the build is meant to be operated
# rather than only benchmarked.
if [ -n "${NATIVE_MONITORING:-}" ]; then
  gradle_args="$gradle_args -PnativeMonitoring=$NATIVE_MONITORING"
fi

# NATIVE_OPTIMIZATION=s spends throughput to halve the image. The default is 3,
# which is 10% faster where memory is not the constraint and costs only registry
# space — with a heap ceiling in place both levels peak at the same resident size.
if [ -n "${NATIVE_OPTIMIZATION:-}" ]; then
  gradle_args="$gradle_args -PnativeOptimization=$NATIVE_OPTIMIZATION -PnanofaasBuildOptimization=$NATIVE_OPTIMIZATION"
fi

# NATIVE_BUILD_MEMORY=6g bounds the builder, not the built image. native-image
# reads the machine's total memory to size its own heap and does not know what
# else is running: on a 12GB VM already holding k3s, a control plane and
# Prometheus it was OOM-killed after nearly ten minutes. Set this wherever the
# build shares a machine with anything.
if [ -n "${NATIVE_BUILD_MEMORY:-}" ]; then
  gradle_args="$gradle_args -PnativeBuildMemory=$NATIVE_BUILD_MEMORY"
fi
if [ -n "${NATIVE_PARALLELISM:-}" ]; then
  gradle_args="$gradle_args -PnativeParallelism=$NATIVE_PARALLELISM"
fi

build=(docker build --file deploy/native-java/Dockerfile --tag "$image")
if [ -n "${IMAGE_PLATFORM:-}" ]; then
  build+=(--platform "$IMAGE_PLATFORM")
fi
build+=(
  --build-arg "NATIVE_TASK=$task"
  --build-arg "NATIVE_BINARY=$binary"
  --build-arg "GRADLE_ARGS=$gradle_args"
  --build-arg "GRAALVM_DISTRIBUTION=$graalvm_distribution"
  .
)

"${build[@]}"
