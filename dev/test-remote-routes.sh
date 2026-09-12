#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="$HOME/.cache/yxi-route-tests"
mkdir -p "$CACHE"
chmod 700 "$CACHE"
FIXTURE=$(mktemp -d "$CACHE/fixture.XXXXXX")
mkdir -p "$FIXTURE/home/.claude" "$FIXTURE/home/.codex" "$FIXTURE/home/project"
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/host"
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/client"
python3 - "$FIXTURE" <<'PY'
import pathlib,socket,sys
p=pathlib.Path(sys.argv[1])
with socket.socket() as s:
    s.bind(('127.0.0.1',0)); (p/'port').write_text(str(s.getsockname()[1]))
(p/'home/.claude/settings.json').write_text('{"permissions":{"allow":["Read"]}}')
(p/'home/.codex/config.toml').write_text('model = "original-model"\n')
(p/'command').write_text('#!/bin/sh\nexport HOME="'+str(p/'home')+'"\ncd "$HOME" || exit 1\nexec /bin/sh -c "$SSH_ORIGINAL_COMMAND"\n')
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
trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT
sleep 1
kill -0 "$server"
cd "$ROOT/android"
YXI_ROUTE_FIXTURE="$FIXTURE" ./gradlew :desktop:test --tests app.yxi.desktop.RemoteRoutesTest --rerun --no-daemon
echo "Isolated SSH route fixture: $FIXTURE"
