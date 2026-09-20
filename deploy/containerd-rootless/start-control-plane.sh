#!/usr/bin/env bash
set -euo pipefail

die() { printf 'nanofaas launcher: %s\n' "$*" >&2; exit 1; }
absolute() { [[ ${!1:-} == /* ]] || die "$1 must be an absolute path"; }

for name in NANOFAAS_ROOT HOME XDG_RUNTIME_DIR; do
  absolute "$name"
done
for name in NANOFAAS_CONTAINERD_SOCKETPATH NANOFAAS_CONTAINERD_CNIPLUGINDIRECTORY \
    NANOFAAS_CONTAINERD_CNICONFIGDIRECTORY NANOFAAS_CONTAINERD_CNICACHEDIRECTORY \
    NANOFAAS_CONTAINERD_STATEDIRECTORY; do
  if [[ -n ${!name:-} ]]; then absolute "$name"; fi
done
[[ -d $NANOFAAS_ROOT ]] || die "NANOFAAS_ROOT does not exist: $NANOFAAS_ROOT"

mode=${NANOFAAS_CONTROL_PLANE_MODE:-jvm}
artifact=${NANOFAAS_CONTROL_PLANE_ARTIFACT:-}
case $mode in
  jvm) artifact=${artifact:-$NANOFAAS_ROOT/platform/control-plane/build/libs/app.jar} ;;
  native) [[ -n $artifact ]] || die 'native mode requires NANOFAAS_CONTROL_PLANE_ARTIFACT' ;;
  *) die "invalid control-plane mode (NANOFAAS_CONTROL_PLANE_MODE): $mode" ;;
esac
[[ $artifact == /* ]] || die 'control-plane artifact must be an absolute path'
[[ -f $artifact ]] || die "control-plane artifact does not exist: $artifact"
if [[ $mode == native ]]; then
  [[ -x $artifact ]] || die "native control-plane artifact is not executable: $artifact"
fi

child_pid_file=$XDG_RUNTIME_DIR/containerd-rootless/child_pid
[[ -r $child_pid_file ]] || die "RootlessKit child PID file is missing: $child_pid_file"
child_pid=$(<"$child_pid_file")
[[ $child_pid =~ ^[1-9][0-9]*$ ]] || die "invalid RootlessKit child PID: $child_pid"

cd "$NANOFAAS_ROOT"
if [[ $mode == jvm ]]; then
  exec nsenter -t "$child_pid" -U --preserve-credentials -n -m -- \
    env HOME="$HOME" XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \
    java --enable-native-access=ALL-UNNAMED "-Duser.home=$HOME" -jar "$artifact"
fi

native_args=${NANOFAAS_CONTROL_PLANE_NATIVE_ARGS:-}
[[ $native_args != *$'\n'* ]] || die 'NANOFAAS_CONTROL_PLANE_NATIVE_ARGS must be a single line'
read -r -a argv <<< "$native_args" || true
exec nsenter -t "$child_pid" -U --preserve-credentials -n -m -- \
  env HOME="$HOME" XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" "$artifact" "${argv[@]}"
