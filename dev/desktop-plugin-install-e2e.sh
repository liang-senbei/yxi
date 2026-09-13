#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export DISPLAY=${YXI_DISPLAY:-:125}
OUT=${YXI_E2E_OUT:-/tmp/yxi-plugin-install-ui}
mkdir -p "$OUT"
test ! -e "/tmp/.X11-unix/X${DISPLAY#:}"
Xvfb "$DISPLAY" -screen 0 1400x1000x24 >"$OUT/display.log" 2>&1 & display_pid=$!
wm_pid=; test_pid=
trap '[ -z "$test_pid" ] || kill -- "-$test_pid" 2>/dev/null || true; kill "$display_pid" ${wm_pid:+"$wm_pid"} 2>/dev/null || true' EXIT
sleep 2
xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
cd "$ROOT"
setsid timeout --kill-after=10s 180s env YXI_TEST_PLUGIN_INSTALL=1 bash dev/test-remote-routes.sh >"$OUT/test.log" 2>&1 & test_pid=$!
fixture=
for attempt in $(seq 1 120); do
  fixture=$(awk '/^Fixture:/{print $2;exit}' "$OUT/test.log")
  [ -n "$fixture" ] && [ -f "$fixture/ready" ] && break
  kill -0 "$test_pid" || { cat "$OUT/test.log"; exit 1; }
  sleep 1
done
test -f "$fixture/ready"
tap() { xdotool mousemove "$1" "$2" click 1; sleep 1; }
tap 158 477
import -window root "$fixture/02-options.png"
tap 600 662
sleep 2
import -window root "$fixture/03-review.png"
xdotool key Escape
sleep 1
touch "$fixture/cancel-check"
for attempt in $(seq 1 30); do [ -f "$fixture/cancel-verified" ] && break; sleep 1; done
test -f "$fixture/cancel-verified"
tap 158 477
tap 600 662
sleep 2
tap 620 619
wait "$test_pid"
test_pid=
echo "Plugin installation UI verified: $fixture"
