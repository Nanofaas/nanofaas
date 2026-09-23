#!/bin/sh
# Fails unless every argument is a native-image EXECUTABLE.
#
# Why this exists (issue #208, Task 13b and the residual it left): native-image build-tools
# registers `options.getSharedLibrary().convention(true)` for any module that applies BOTH
# `java-library` and `org.graalvm.buildtools.native`, and `nativeCompile` still exits 0. From
# d456915f to 6b09b21d that is what `:control-plane` did: the release path's binary was a shared
# library that nothing could execute, and it stayed that way for 33 commits and a day because no
# job in CI compiled a native image at all. `file` is the cheapest observable that separates the
# two outcomes, and it is what a human already had to run by hand to find it.
#
# Usage: scripts/assert-native-executable.sh <native-binary>...
set -eu

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <native-binary>..." >&2
  exit 2
fi

status=0
for binary in "$@"; do
  if [ ! -e "$binary" ]; then
    echo "FAIL: native artifact is missing: $binary" >&2
    status=1
    continue
  fi

  description=$(file -b "$binary")
  echo "$binary: $description"

  case "$description" in
    *executable*)
      if [ ! -x "$binary" ]; then
        echo "FAIL: $binary is described as an executable but has no execute bit" >&2
        status=1
      fi
      ;;
    *)
      echo "FAIL: $binary is not an executable." >&2
      echo "      The module that built it almost certainly applies 'java-library' together with" >&2
      echo "      'org.graalvm.buildtools.native', which makes native-image emit a shared library." >&2
      echo "      Set 'sharedLibrary.set(false)' on its 'main' binary (see" >&2
      echo "      platform/control-plane/build.gradle) and rebuild." >&2
      status=1
      ;;
  esac
done

exit "$status"
