#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=${YXI_DISPLAY:-:119}
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-support-ui.XXXXXX)}
mkdir -p "$OUT/profile"
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
cd "$ROOT/android"
setsid env DISPLAY="$D" YXI_SUPPORT_UI_FIXTURE="$OUT" ./gradlew :desktop:test :desktop:packageUberJarForCurrentOS --no-daemon >"$OUT/test.log" 2>&1 & build_pid=$!
window=
for attempt in $(seq 1 90); do
  window=$(DISPLAY=$D xdotool search --name '^Yxi support fixture$' 2>/dev/null | head -1) || true
  [ -n "$window" ] && break
  kill -0 "$build_pid" || { cat "$OUT/test.log"; exit 1; }
  sleep 1
done
test -n "$window"
sleep 3
tap() { DISPLAY=$D xdotool mousemove "$1" "$2" click 1; sleep 2; }
shot() { DISPLAY=$D import -window root "$OUT/$1.png"; }
tap 220 195
shot 01-support-open
echo "Support fixture ready on $D; evidence: $OUT"
tap 449 445
shot 02-reply-editor
tap 620 353
DISPLAY=$D xdotool type --clearmodifiers --delay 30 'REPLY_FROM_DESKTOP'
sleep 1
tap 449 541
shot 03-reply-confirmed
tap 1064 126
tap 529 313
tap 621 412
DISPLAY=$D xdotool type --clearmodifiers --delay 30 'NEW_FROM_DESKTOP'
sleep 1
shot 04-create-filled
tap 449 660
shot 05-create-confirmed
tap 114 126
wait "$build_pid"
build_pid=
echo 'Support UI verified'
