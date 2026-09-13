#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DISPLAY=${YXI_DISPLAY:-:129}
export DISPLAY
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-host-recovery.XXXXXX)}
mkdir -p "$OUT"
test ! -e "/tmp/.X11-unix/X${DISPLAY#:}" || { echo 'Display busy' >&2; exit 1; }
display_pid=; wm_pid=
cleanup() { for pid in "$wm_pid" "$display_pid"; do [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
Xvfb "$DISPLAY" -screen 0 1400x1000x24 >"$OUT/display.log" 2>&1 & display_pid=$!
sleep 2
xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
cd "$ROOT/android"
YXI_HOST_RECOVERY_UI_OUT="$OUT" ./gradlew :desktop:test --tests '*HostRecoveryUiTest' --no-daemon >"$OUT/test.log" 2>&1
echo "Host recovery UI verified: $OUT"
