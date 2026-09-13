#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=${YXI_DISPLAY:-:121}
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-service-ui.XXXXXX)}
mkdir -p "$OUT"
test ! -e "/tmp/.X11-unix/X${D#:}" || { echo 'Display busy' >&2; exit 1; }
build_pid=; wm_pid=; display_pid=
cleanup() {
  [ -z "$build_pid" ] || kill -- "-$build_pid" 2>/dev/null || true
  for pid in "$wm_pid" "$display_pid"; do [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
Xvfb "$D" -screen 0 1400x1000x24 >"$OUT/display.log" 2>&1 & display_pid=$!
sleep 2
DISPLAY=$D xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
cd "$ROOT"
setsid env DISPLAY="$D" YXI_TEST_SERVICE_UI=1 bash dev/test-remote-routes.sh >"$OUT/test.log" 2>&1 & build_pid=$!
window=
for attempt in $(seq 1 90); do
  window=$(DISPLAY=$D xdotool search --name '^Yxi service fixture$' 2>/dev/null | head -1) || true
  [ -n "$window" ] && break
  kill -0 "$build_pid" || { cat "$OUT/test.log"; exit 1; }
  sleep 1
done
test -n "$window"
sleep 12
DISPLAY=$D import -window root "$OUT/01-service-ready.png"
fixture=$(awk '/^Fixture:/{print $2;exit}' "$OUT/test.log")
echo "Service UI ready: $OUT; fixture: $fixture; port: $(cat "$fixture/service-port")"
tap() { DISPLAY=$D xdotool mousemove "$1" "$2" click 1; sleep 2; }
shot() { DISPLAY=$D import -window root "$OUT/$1.png"; }
tap 499 101
shot 02-configure
tap 350 460
DISPLAY=$D xdotool type --clearmodifiers --delay 20 "python3 -m http.server $(cat "$fixture/service-port") --bind 127.0.0.1"
tap 614 663
sleep 2
shot 03-configured
tap 599 251
sleep 4
tap 663 252
sleep 8
shot 04-running
tap 300 353
DISPLAY=$D xdotool key ctrl+a
sleep 1
DISPLAY=$D xdotool key ctrl+c
sleep 1
DISPLAY=$D timeout 5 xclip -selection clipboard -o > "$OUT/page.txt"
grep -q UI_DEV_SERVICE_READY "$OUT/page.txt"
tap 719 252
tap 671 309
shot 05-logs
DISPLAY=$D xdotool key Escape
sleep 1
tap 599 252
sleep 2
shot 06-stopped
tap 733 64
wait "$build_pid"
build_pid=
echo 'Service UI verified'
