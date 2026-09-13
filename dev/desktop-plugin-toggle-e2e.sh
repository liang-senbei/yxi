#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export DISPLAY=${YXI_DISPLAY:-:124}
OUT=${YXI_E2E_OUT:-/tmp/yxi-plugin-toggle-ui}
mkdir -p "$OUT"
test ! -e "/tmp/.X11-unix/X${DISPLAY#:}"
Xvfb "$DISPLAY" -screen 0 1400x1000x24 >"$OUT/display.log" 2>&1 & display_pid=$!
wm_pid=
trap 'kill "$display_pid" ${wm_pid:+"$wm_pid"} 2>/dev/null || true' EXIT
sleep 2
xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
cd "$ROOT"
YXI_TEST_PLUGIN_TOGGLE=1 bash dev/test-remote-routes.sh >"$OUT/test.log" 2>&1