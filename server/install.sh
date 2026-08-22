#!/usr/bin/env bash
# yxi 服务器侧安装 —— 就一件事：把 yxi-hook 注册进 Claude Code 的 settings.json。
#
# ⚠️ 它会改**你所有 Claude Code 会话共用的**配置文件，所以：
#   · 先备份（settings.json.yxi-bak-<时间戳>）
#   · 幂等：重复跑不会重复注册
#   · 写完立刻用 python 校验 JSON，坏了就自动回滚
#   · `--uninstall` 一键摘掉
#
# 装完不需要重启任何东西，也不占端口、不起守护进程。
set -euo pipefail

HOOK_SRC="$(cd "$(dirname "$0")" && pwd)/yxi-hook"
BIN_DIR="${HOME}/.local/bin"
HOOK_DST="${BIN_DIR}/yxi-hook"
SETTINGS="${HOME}/.claude/settings.json"
EVENTS_DIR="${HOME}/.yxi"
EVENTS="${EVENTS_DIR}/events.jsonl"

# 这三种事件才值得让手机响。PreToolUse 之类每秒好几次，注册了等于把手机变成骚扰源
EVENTS_LIST='Stop Notification SessionEnd'

uninstall() {
  python3 - "$SETTINGS" <<'PY'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1])
if not p.exists(): sys.exit(0)
d = json.loads(p.read_text())
hooks = d.get("hooks", {})
for ev, groups in list(hooks.items()):
    for g in groups:
        g["hooks"] = [h for h in g.get("hooks", []) if "yxi-hook" not in str(h.get("command", ""))]
    hooks[ev] = [g for g in groups if g.get("hooks")]
    if not hooks[ev]: del hooks[ev]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2))
print("已从 settings.json 摘掉 yxi-hook")
PY
  rm -f "$HOOK_DST"
  echo "（$EVENTS 留着没删 —— 里面是历史事件，要删自己动手）"
}

if [ "${1:-}" = "--uninstall" ]; then uninstall; exit 0; fi

mkdir -p "$BIN_DIR" "$EVENTS_DIR"
install -m 755 "$HOOK_SRC" "$HOOK_DST"
touch "$EVENTS"; chmod 600 "$EVENTS"
echo "· 装好 $HOOK_DST"

mkdir -p "$(dirname "$SETTINGS")"
[ -f "$SETTINGS" ] || echo '{}' > "$SETTINGS"
BAK="${SETTINGS}.yxi-bak-$(date +%s)"
cp "$SETTINGS" "$BAK"
echo "· 备份 $BAK"

python3 - "$SETTINGS" "$HOOK_DST" "$EVENTS_LIST" <<'PY'
import json, sys, pathlib
settings, hook, events = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3].split()
d = json.loads(settings.read_text() or "{}")
hooks = d.setdefault("hooks", {})
added = []
for ev in events:
    groups = hooks.setdefault(ev, [])
    already = any("yxi-hook" in str(h.get("command", ""))
                  for g in groups for h in g.get("hooks", []))
    if already: continue
    # 单独一组，跟别人（比如 cc-state）的 hook 井水不犯河水
    # async=true：Claude Code 不等它返回 —— 通知这件事没有任何理由拖慢正事
    groups.append({"hooks": [{"type": "command", "command": hook, "async": True}]})
    added.append(ev)
settings.write_text(json.dumps(d, ensure_ascii=False, indent=2))
print("· 注册了：" + (", ".join(added) if added else "（已经装过，没重复加）"))
PY

# 写完立刻校验；坏了就回滚 —— 这个文件坏掉会影响这台机器上**每一个** Claude Code 会话
if ! python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$SETTINGS" 2>/dev/null; then
  cp "$BAK" "$SETTINGS"
  echo "✗ settings.json 校验失败，已回滚到 $BAK"; exit 1
fi
echo "· settings.json 校验通过"

printf '{"hook_event_name":"Stop","cwd":"%s"}' "$PWD" | "$HOOK_DST"
if tail -1 "$EVENTS" 2>/dev/null | grep -q '"kind": "done"'; then
  echo "· 自测通过：事件写进了 $EVENTS"
else
  echo "✗ 自测失败：$EVENTS 没收到事件"; exit 1
fi
echo
echo "装好了。不用重启任何东西。摘掉：$0 --uninstall"
