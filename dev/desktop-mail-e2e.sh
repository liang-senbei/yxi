#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=${YXI_DISPLAY:-:119}
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-mail-ui.XXXXXX)}
mkdir -p "$OUT/profile"
test ! -e "/tmp/.X11-unix/X${D#:}" || { echo 'Display busy' >&2; exit 1; }
build_pid=; wm_pid=; display_pid=
cleanup() {
  [ -z "$build_pid" ] || kill -- "-$build_pid" 2>/dev/null || true
  for pid in "$wm_pid" "$display_pid"; do [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
Xvfb "$D" -screen 0 1400x900x24 >"$OUT/display.log" 2>&1 & display_pid=$!
sleep 2
DISPLAY=$D xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
cd "$ROOT/android"
setsid env DISPLAY="$D" YXI_MAIL_UI_FIXTURE="$OUT" ./gradlew :desktop:test :desktop:packageUberJarForCurrentOS --no-daemon >"$OUT/test.log" 2>&1 & build_pid=$!
window=
for attempt in $(seq 1 90); do
  window=$(DISPLAY=$D xdotool search --name '^Yxi mail fixture$' 2>/dev/null | head -1) || true
  [ -n "$window" ] && break
  kill -0 "$build_pid" || { cat "$OUT/test.log"; exit 1; }
  sleep 1
done
test -n "$window"
sleep 3
tap() { DISPLAY=$D xdotool mousemove "$1" "$2" click 1; sleep 2; }
shot() { DISPLAY=$D import -window root "$OUT/$1.png"; }
tap 250 215
shot 01-mail-open
echo "Mail fixture ready on $D; evidence: $OUT"
tap 449 496
shot 02-claimed
tap 485 526
shot 03-delete-review
tap 619 503
python3 - "$OUT/actions.txt" <<'PY'
import pathlib,sys
assert '/delete' not in pathlib.Path(sys.argv[1]).read_text()
PY
tap 485 526
tap 684 503
shot 04-deleted
tap 114 125
wait "$build_pid"
build_pid=
echo 'Mail UI verified'
