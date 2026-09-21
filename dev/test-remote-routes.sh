#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'Disabled after the tmux isolation incident. Migrate this SSH fixture to a dedicated container before enabling it.' >&2
exit 78
# Agent 会话本身就跑在 tmux 里，TMUX 环境变量指向**绝对 socket 路径**且优先于 TMUX_TMPDIR——
# 不 strip 掉的话，下面 trap 里的 `tmux kill-server` 会命中宿主默认服务（本组 Agent 全在上面）。
unset TMUX TMUX_PANE
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="$HOME/.cache/yxi-route-tests"
mkdir -p "$CACHE"
chmod 700 "$CACHE"
FIXTURE=$(mktemp -d "$CACHE/fixture.XXXXXX")
FIXTURE_TMUX_SOCKET="$FIXTURE/tmux/tmux-$(id -u)/default"
mkdir -p "$FIXTURE/home/.claude" "$FIXTURE/home/.codex" "$FIXTURE/home/project" "$FIXTURE/home/.local/bin" "$FIXTURE/tmux"
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/host"
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/client"
python3 - "$FIXTURE" <<'PY'
import pathlib,socket,sys
p=pathlib.Path(sys.argv[1])
with socket.socket() as s:
    s.bind(('127.0.0.1',0)); (p/'port').write_text(str(s.getsockname()[1]))
(p/'home/.claude/settings.json').write_text('{"permissions":{"allow":["Read"]}}')
(p/'home/.codex/config.toml').write_text('model = "original-model"\n')
(p/'command').write_text('#!/bin/sh\nunset TMUX TMUX_PANE\nexport HOME="'+str(p/'home')+'"\nexport TMUX_TMPDIR="'+str(p/'tmux')+'"\nexport PATH=/usr/bin:/bin\ncd "$HOME" || exit 1\nexec /bin/sh -c "$SSH_ORIGINAL_COMMAND"\n')
for name in ('claude','codex'):
    runtime=p/'home/.local/bin'/name
    runtime.write_text('#!/bin/sh\nexec sleep 600\n')
    runtime.chmod(0o700)
PY
chmod 700 "$FIXTURE/command"
cat > "$FIXTURE/sshd_config" <<EOF
ListenAddress 127.0.0.1
Port $(cat "$FIXTURE/port")
HostKey $FIXTURE/host
PidFile $FIXTURE/sshd.pid
AuthorizedKeysFile $FIXTURE/client.pub
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
PermitRootLogin prohibit-password
AllowUsers root
Subsystem sftp /usr/lib/openssh/sftp-server
ForceCommand $FIXTURE/command
EOF
/usr/sbin/sshd -D -e -f "$FIXTURE/sshd_config" > "$FIXTURE/sshd.log" 2>&1 & server=$!
http_server=
trap 'env -u TMUX -u TMUX_PANE tmux -S "$FIXTURE/home/.yxi/preview-runtime/tmux.sock" kill-server 2>/dev/null || true; env -u TMUX -u TMUX_PANE tmux -S "$FIXTURE_TMUX_SOCKET" kill-server 2>/dev/null || true; kill "$server" ${http_server:+"$http_server"} 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT
sleep 1
kill -0 "$server"
python3 - "$FIXTURE" > "$FIXTURE/http.log" 2>&1 <<'PY' &
import http.server,pathlib,sys,time,hashlib,base64,struct
root=pathlib.Path(sys.argv[1])
def payload():
    state=root/'browser-state.txt'
    return state.read_bytes() if state.exists() else b'preview-forward-fixture'
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get('Upgrade','').lower()=='websocket':
            accept=base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101); self.send_header('Upgrade','websocket'); self.send_header('Connection','Upgrade'); self.send_header('Sec-WebSocket-Accept',accept); self.end_headers()
            previous=None
            try:
                for _ in range(300):
                    data=payload()
                    if data!=previous:
                        header=bytes([0x81,len(data)]) if len(data)<126 else b'\x81\x7e'+struct.pack('!H',len(data))
                        self.connection.sendall(header+data); previous=data
                    time.sleep(.1)
            except (BrokenPipeError,ConnectionResetError): pass
            return
        self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.end_headers(); self.wfile.write(payload())
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
(root/'http-port').write_text(str(server.server_port))
server.serve_forever()
PY
http_server=$!
for attempt in $(seq 1 30); do test -f "$FIXTURE/http-port" && break; sleep 0.1; done
test -f "$FIXTURE/http-port"
cd "$ROOT/android"
echo "Fixture: $FIXTURE"
if [ "${YXI_TEST_PLUGIN_INSTALL:-0}" = 1 ]; then
  YXI_PLUGIN_INSTALL_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.PluginInstallFixtureTest --no-daemon
elif [ "${YXI_TEST_PLUGIN_TOGGLE:-0}" = 1 ]; then
  YXI_PLUGIN_TOGGLE_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.PluginToggleFixtureTest --no-daemon
elif [ "${YXI_TEST_SERVICE_UI:-0}" = 1 ]; then
  YXI_SERVICE_UI_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.ServiceControlsFixtureTest --no-daemon
elif [ "${YXI_TEST_BROWSER:-0}" = 1 ]; then
  YXI_ROUTE_FIXTURE="$FIXTURE" YXI_BROWSER_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.BrowserIntegrationTest --rerun --no-daemon
elif [ "${YXI_TEST_DEFERRED:-0}" = 1 ]; then
  # 0cb6030 延后线路切换执行层（runDeferredRoute）：断线/取消 + fixture 各阶段
  YXI_ROUTE_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.DeferredRouteRunnerTest --rerun --no-daemon
else
  YXI_ROUTE_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.RemoteRoutesTest --rerun --no-daemon
fi
cp -r "$ROOT/android/desktop/build/test-results/test" "$FIXTURE/test-results"
echo "Isolated SSH route fixture: $FIXTURE"
