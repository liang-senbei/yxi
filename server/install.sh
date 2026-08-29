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

# 这几种事件才值得让手机响。PreToolUse 之类每秒好几次，注册了等于把手机变成骚扰源。
#
# ⚠️ `PermissionRequest` 是**为了通知正文**加的：它带 `tool_input`，
# 也就是「Claude 到底想跑哪条命令」的原文。没有它，通知只能写一句
# 「需要你」，用户得点开 App 才知道要批什么 —— 那这条通知就白发了。
# 实测它**只在提示真的渲染出来时才触发**，比 Notification 准。
EVENTS_LIST='Stop Notification SessionEnd PermissionRequest'

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

# 发布一个新版本给手机自更新用：把 APK 和清单摆进 ~/.yxi/
#   ./install.sh --publish <apk> <versionCode> <versionName> [说明]
# 手机连上这台机器时会看到「有新版本」，走 SFTP 下载 ——
# 不用 GitHub、不用 token、不用公网 HTTP，防火墙后面照样能用。
if [ "${1:-}" = "--publish" ]; then
  APK="${2:?用法: $0 --publish <apk> <versionCode> <versionName> [说明]}"
  CODE="${3:?缺 versionCode}"; NAME="${4:?缺 versionName}"; NOTES="${5:-}"
  [ -f "$APK" ] || { echo "找不到 $APK"; exit 1; }
  mkdir -p "$EVENTS_DIR"
  install -m 644 "$APK" "$EVENTS_DIR/Yxi.apk"
  python3 -c 'import json,sys,pathlib; pathlib.Path(sys.argv[1]).write_text(json.dumps({"versionCode":int(sys.argv[2]),"versionName":sys.argv[3],"file":"Yxi.apk","notes":sys.argv[4]},ensure_ascii=False,indent=1))' \
    "$EVENTS_DIR/latest.json" "$CODE" "$NAME" "$NOTES"
  echo "· 发布好了：$EVENTS_DIR/Yxi.apk（$(du -h "$EVENTS_DIR/Yxi.apk" | cut -f1)）"
  echo "  清单 → versionCode $CODE / $NAME"
  echo "  ⚠️ versionCode 必须比上一版大 —— 手机只比这个数，versionName 只给人看。"

  # 顺手推到公网下载机（hk13）。手机点「检查更新」走的是**那台的 HTTP**，
  # 不是这台的 SFTP —— 只发本地等于没发。
  #
  # ⚠️ **必须比对 sha256。** 踩过一次（TROUBLESHOOTING #69）：三处路径各自
  #    「看起来都成功了」，用户手机上下到的还是旧包。传完不校验 = 没传。
  # ⚠️ 推失败不回滚本地发布（SFTP 自更新照样能用），但要**吼**，
  #    否则静悄悄留下一个「本地新、公网旧」的裂口。
  TOKEN="$EVENTS_DIR/dl-token"
  if [ ! -r "$TOKEN" ]; then
    echo "  ⚠️ 没有 $TOKEN，跳过公网同步 —— 手机上还是旧包。"
  elif ! ssh -o BatchMode=yes -o ConnectTimeout=8 hk13 true 2>/dev/null; then
    echo "  ⚠️ 连不上 hk13，跳过公网同步 —— 手机上还是旧包。"
  else
    DST="/var/www/yxi/$(cat "$TOKEN")"
    scp -q "$EVENTS_DIR/Yxi.apk" "$EVENTS_DIR/latest.json" "hk13:$DST/"
    HERE=$(sha256sum "$EVENTS_DIR/Yxi.apk" | cut -d" " -f1)
    THERE=$(ssh hk13 "sha256sum $DST/Yxi.apk" | cut -d" " -f1)
    if [ "$HERE" = "$THERE" ]; then
      echo "  · 已同步到 hk13，sha256 一致：${HERE:0:12}…"
    else
      echo "  ❌ hk13 上的包对不上！本地 ${HERE:0:12}… ≠ 远端 ${THERE:0:12}…"
      echo "     手机会下到错的东西，手动查一下 $DST"
      exit 1
    fi
  fi
  exit 0
fi

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
    # ⚠️⚠️ async=true 不只是「别拖慢正事」，它是一道**结构性护栏**：
    # 带了它 hook 就无法阻塞、也就无法返回 permissionDecision。
    # 实测阻塞式 hook 返回 allow 时，**屏幕上一个提示都不曾出现** ——
    # 「自动放行」会变成一个 if 写错就发生、且事后查不出来的事；
    # 而且阻塞期间坐在键盘前的人**没有任何东西可以按**（桌面被废掉）。
    # 见 TROUBLESHOOTING #145。**别去掉这个 true。**
    g = {"hooks": [{"type": "command", "command": hook, "async": True}]}
    # ⚠️ `Notification` 混着 `idle_prompt`（空闲 60 秒的提醒，文案是
    # "Claude is waiting for your input"）—— 不加 matcher 的话，
    # 一个闲了一分钟的会话也会把手机叫醒。这是「手机白响」的来源之一。
    if ev == "Notification":
        g["matcher"] = "permission_prompt|agent_needs_input"
    groups.append(g)
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
