#!/usr/bin/env bash
# A real SSH/SFTP document preview against disposable files and an inert tmux session.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=${YXI_DISPLAY:-:118}
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-doc-e2e.XXXXXX)}
TEST_HOME=$(mktemp -d /tmp/yxi-doc-home.XXXXXX)
FIXTURE=$(mktemp -d /tmp/yxi-doc-fixture.XXXXXX)
SESSION=cc-yxi-doc-test-$$
KEY=${YXI_TEST_SSH_KEY:?Set YXI_TEST_SSH_KEY to a key authorized on localhost}
mkdir -p "$OUT" "$TEST_HOME/.config/yxi"
app_pid=; wm_pid=; display_pid=; web_pid=
cleanup() {
  for pid in "$app_pid" "$wm_pid" "$display_pid" "$web_pid"; do
    [ -z "$pid" ] || { kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; }
  done
  tmux kill-session -t "$SESSION" 2>/dev/null || true
}
trap cleanup EXIT
# Host keys come from the trusted local server files, never from unverified ssh-keyscan.
for pub in /etc/ssh/ssh_host_*_key.pub; do awk '{print "127.0.0.1 " $1 " " $2}' "$pub"; done > "$TEST_HOME/.config/yxi/known_hosts"
python3 - "$TEST_HOME" "$KEY" "$SESSION" "$FIXTURE" <<'PY'
import json,pathlib,sys
home,key,session,fixture=sys.argv[1:]
p=pathlib.Path(home)/'.config/yxi'
(p/'hosts.json').write_text(json.dumps([dict(id='doc-test',alias='Preview test',hostname='127.0.0.1',username='root',keyPath=key)]))
(p/'prefs.json').write_text(json.dumps(dict(lastHost='doc-test',lastSession=session,theme='light')))
(pathlib.Path(fixture)/'PRD.md').write_text('# Live document preview\n\n| Feature | Status |\n| --- | --- |\n| Remote files | Ready |\n| Markdown tables | Ready |\n\nEdit this document beside the conversation.\n')
PY
tmux new-session -d -s "$SESSION" -c "$FIXTURE" 'sleep 600'
browser_args=()
if [ "${YXI_TEST_BROWSER:-0}" = 1 ]; then
  WEB_FIXTURE=$(mktemp -d /tmp/yxi-browser-page.XXXXXX)
  echo "Web fixture: $WEB_FIXTURE"
  python3 "$ROOT/dev/browser-ui-fixture.py" "$WEB_FIXTURE" > "$OUT/web.log" 2>&1 & web_pid=$!
  for attempt in $(seq 1 30); do test -f "$WEB_FIXTURE/browser-port" && break; sleep .1; done
  test -f "$WEB_FIXTURE/browser-port"
  browser_args=(-Dyxi.browser.localFixture=true -Dyxi.browser.runtimeDir=/root/.cache/yxi-browser-146)
fi
test ! -e "/tmp/.X11-unix/X${D#:}" || { echo "Display busy" >&2; exit 1; }
Xvfb "$D" -screen 0 1400x900x24 >"$OUT/display.log" 2>&1 & display_pid=$!
sleep 2
DISPLAY=$D xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
jar="$ROOT/android/desktop/build/compose/jars/Yxi-linux-x64-1.2.0.jar"
DISPLAY=$D "${JAVA_HOME:+$JAVA_HOME/bin/}java" "${browser_args[@]}" -Duser.home="$TEST_HOME" -jar "$jar" >"$OUT/run.log" 2>&1 & app_pid=$!
for _ in $(seq 1 30); do
  DISPLAY=$D xdotool search --name '^Yxi$' >/dev/null 2>&1 && break
  sleep 1
done
sleep 12
tap() { DISPLAY=$D xdotool mousemove "$1" "$2" click 1; sleep 3; }
shot() { DISPLAY=$D import -window root "$OUT/$1.png"; }
shot 01-restored
tap 500 110
shot 02-files
tap 480 182
shot 03-preview
printf '\n\n## LIVE_UPDATE_CONFIRMED\n' >> "$FIXTURE/PRD.md"
sleep 5
shot 04-updated
tap 855 271
tap 930 368
DISPLAY=$D xdotool key ctrl+a
sleep 1
shot 04-selection
DISPLAY=$D xdotool type --clearmodifiers '# SAVED_FROM_DESKTOP'
DISPLAY=$D xdotool key ctrl+s
saved=0
for _ in $(seq 1 15); do
  if grep -q SAVED_FROM_DESKTOP "$FIXTURE/PRD.md"; then saved=1; break; fi
  sleep 1
done
shot 05-saved
test "$saved" = 1 || { echo 'Desktop save did not reach the remote document' >&2; exit 1; }
test "$(cat "$FIXTURE/PRD.md")" = '# SAVED_FROM_DESKTOP' || { echo 'Select-all/edit/save did not replace the selected document' >&2; exit 1; }
DISPLAY=$D xdotool key ctrl+a
sleep 1
DISPLAY=$D xdotool type --clearmodifiers '# LOCAL_UNSAVED'
printf '# REMOTE_CONCURRENT\n' > "$FIXTURE/PRD.md"
sleep 5
shot 06-conflict
grep -q REMOTE_CONCURRENT "$FIXTURE/PRD.md"
DISPLAY=$D xdotool key ctrl+a
sleep 1
tap 840 824
shot 07-quoted
if [ "${YXI_TEST_BROWSER:-0}" = 1 ]; then
  tap 1180 110
  tap 940 208
  DISPLAY=$D xdotool key ctrl+a
  sleep 1
  DISPLAY=$D xdotool type --clearmodifiers "$(cat "$WEB_FIXTURE/browser-port")"
  DISPLAY=$D xdotool key Return
  sleep 12
  shot 08-browser
  printf 'LIVE UPDATE CONFIRMED' > "$WEB_FIXTURE/browser-live.txt"
  sleep 3
  shot 09-browser-live
  tap 984 262
  tap 950 392
  shot 10-browser-selected
  if [ "${YXI_TEST_STYLES:-0}" = 1 ]; then
    original_page_hash=$(sha256sum "$WEB_FIXTURE/index.html" | cut -d ' ' -f 1)
    tap 1190 824
    shot 10-style-controls
    tap 1020 695
    DISPLAY=$D xdotool key ctrl+a
    sleep 1
    DISPLAY=$D xdotool type --clearmodifiers '36'
    DISPLAY=$D xdotool key Return
    sleep 2
    shot 10-style-trial
    test "$(sha256sum "$WEB_FIXTURE/index.html" | cut -d ' ' -f 1)" = "$original_page_hash"
    tap 1190 824
  fi
  tap 950 760
  DISPLAY=$D xdotool type --clearmodifiers 'Make this heading more prominent.'
  tap 1210 159
  shot 10-web-close-review
  DISPLAY=$D xdotool key Escape
  sleep 1
  tap 835 812
  shot 11-browser-feedback
  tap 1152 159
  tap 1180 110
  sleep 2
  shot 12-browser-reopened
  tap 950 392
  DISPLAY=$D xdotool key ctrl+a
  sleep .3
  DISPLAY=$D xdotool key ctrl+c
  sleep .3
  DISPLAY=$D timeout 3 xclip -selection clipboard -o > "$OUT/browser-visible-text.txt"
  grep -q 'LIVE UPDATE CONFIRMED' "$OUT/browser-visible-text.txt"
  tap 1210 159
  shot 13-browser-closed
  kill -0 "$app_pid"
  tap 1225 68
  shot 14-exit-review
  DISPLAY=$D xdotool key Escape
  sleep 1
  shot 14-exit-cancelled
  kill -0 "$app_pid"
  tap 1225 68
  shot 15-exit-second-review
  tap 668 538
  shot 16-exit-confirmed
  for attempt in $(seq 1 30); do kill -0 "$app_pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$app_pid" 2>/dev/null; then echo 'Application did not finish browser shutdown' >&2; exit 1; fi
  wait "$app_pid"
  app_pid=
  if grep -q 'Browser cleanup did not finish' "$OUT/run.log"; then exit 1; fi
else
  tap 1080 110
  shot 08-routes
fi
if [ "${YXI_TEST_NAVIGATION:-0}" = 1 ]; then
  tap 322 714
  shot 09-task-menu
  DISPLAY=$D xdotool key Down Return
  sleep 2
  shot 10-pinned
  python3 - "$TEST_HOME" <<'PY'
import pathlib,json,hashlib,re,sys
p=pathlib.Path(sys.argv[1])/'.config/yxi'
runtime=json.loads((p/'prefs.json').read_text())['lastRuntime']
assert re.fullmatch(r'\d+:\$\d+:\d+',runtime),runtime
key=hashlib.sha256('\0'.join(['doc-test','127.0.0.1','22','root',runtime]).encode()).hexdigest()
assert json.loads((p/'workspace.json').read_text())['tasks'][key]['pin']>0
PY
  tap 322 400
  DISPLAY=$D xdotool key Down Down Down Return
  sleep 2
  tap 216 252
  shot 11-archived
  python3 - "$TEST_HOME" <<'PY'
import pathlib,json,hashlib,sys
p=pathlib.Path(sys.argv[1])/'.config/yxi'
runtime=json.loads((p/'prefs.json').read_text())['lastRuntime']
key=hashlib.sha256('\0'.join(['doc-test','127.0.0.1','22','root',runtime]).encode()).hexdigest()
assert json.loads((p/'workspace.json').read_text())['tasks'][key]['archived'] is True
PY
  tap 322 434
  DISPLAY=$D xdotool key Down Down Down Return
  sleep 2
  tap 86 252
  shot 12-restored-pin
  python3 - "$TEST_HOME" <<'PY'
import pathlib,json,hashlib,sys
p=pathlib.Path(sys.argv[1])/'.config/yxi'
runtime=json.loads((p/'prefs.json').read_text())['lastRuntime']
key=hashlib.sha256('\0'.join(['doc-test','127.0.0.1','22','root',runtime]).encode()).hexdigest()
task=json.loads((p/'workspace.json').read_text())['tasks'][key]
assert task['archived'] is False and task['pin']>0
PY
  tmux has-session -t "$SESSION"
fi
echo "Evidence: $OUT; fixture: $FIXTURE; test home: $TEST_HOME"
if grep -iE 'exception|error' "$OUT/run.log" | grep -viE 'Cannot create Linux GL context|Fallback to next API|Failed global descriptor lookup: 7'; then exit 1; fi
