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
HUB_SRC="$(cd "$(dirname "$0")" && pwd)/yxi-hub"
BIN_DIR="${HOME}/.local/bin"
HOOK_DST="${BIN_DIR}/yxi-hook"
# 同组的 agent 靠它互相说话。⚠️ 装了才「打通」得起来 ——
# 手机把分组写进 ~/.yxi/groups.json，但 agent 得有个东西去读它、去送键。
HUB_DST="${BIN_DIR}/yxi-hub"
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
        g["hooks"] = [h for h in g.get("hooks", [])
                      if "yxi-hook" not in str(h.get("command", ""))
                      and "yxi-hub" not in str(h.get("command", ""))]
    hooks[ev] = [g for g in groups if g.get("hooks")]
    if not hooks[ev]: del hooks[ev]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2))
print("已从 settings.json 摘掉 yxi-hook")
PY
  rm -f "$HOOK_DST"
  echo "（$EVENTS 留着没删 —— 里面是历史事件，要删自己动手）"
}

# ────────────────────────────────────────────────────────────────
# --publish-server：把服务器侧那几个脚本推到公网下载页，给**新客户的一键装机**用：
#   https://yxi.keuury.com/bootstrap.sh          ← 客户在自己服务器上 `curl … | bash`，或手机上点「一键装机」
#   https://yxi.keuury.com/server/{install.sh,yxi-hook,yxi-hub,yxi-lab}   ← bootstrap 从这儿下
# ⚠️ 仓库是私有的，客户拿不到 git；这几个文件里没有任何密钥，公开无妨。
# `--publish`（发 APK）结束时也会顺手跑一遍 —— 手机上那版 App 和它依赖的服务器脚本要一起发。
# ────────────────────────────────────────────────────────────────
publish_server() {
  local here; here="$(cd "$(dirname "$0")" && pwd)"
  if ! ssh -o BatchMode=yes -o ConnectTimeout=8 hk13 true 2>/dev/null; then
    echo "  ⚠️ 连不上 hk13，服务器侧脚本没发到公网。"; return 1
  fi
  ssh hk13 "mkdir -p /var/www/yxi/server"
  scp -q "$here/install.sh" "$here/yxi-hook" "$here/yxi-hub" "$here/yxi-lab" hk13:/var/www/yxi/server/
  scp -q "$here/bootstrap.sh" hk13:/var/www/yxi/bootstrap.sh
  # 传完校验（#69 的教训：传完不校验 = 没传）
  local a b
  a=$(sha256sum "$here/bootstrap.sh" | cut -d" " -f1)
  b=$(curl -fsS --max-time 30 https://yxi.keuury.com/bootstrap.sh | sha256sum | cut -d" " -f1)
  if [ "$a" = "$b" ]; then echo "  · 服务器侧脚本已发到公网（bootstrap.sh sha256 ${a:0:12}…）"
  else echo "  ❌ 公网上的 bootstrap.sh 对不上（CDN 缓存？）：本地 ${a:0:12}… ≠ 公网 ${b:0:12}…"; return 1; fi
}

# 发布一个新版本给手机自更新用：把 APK 和清单摆进 ~/.yxi/
#   ./install.sh --publish <apk> <versionCode> <versionName> [说明]
# 手机连上这台机器时会看到「有新版本」，走 SFTP 下载 ——
# 不用 GitHub、不用 token、不用公网 HTTP，防火墙后面照样能用。
# ────────────────────────────────────────────────────────────────
# --asr：装语音识别（可选，约 360MB）
#
# ⚠️ **不默认装。** 一个连自己服务器的工具，不该强迫用户先在服务器上放 360MB 模型
#    才能用麦克风 —— 没装就退回手机系统那个识别，App 自己会探（`yxi-asr --check`）。
#
# 为什么识别放服务器而不是调云 API：Yxi 没有后端，密钥只能塞进 APK 里 = 公开它。
# 模型是 SenseVoice-Small（阿里 FunAudioLLM，Apache-2.0），走 sherpa-onnx 的 ONNX
# 运行时，**不要 torch**。实测 16 核（被宿主机抢走三到五成）上：5.6 秒中文 1.6 秒解码。
if [ "${1:-}" = "--asr" ]; then
  ASR_DIR="${HOME}/.yxi/asr"
  MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
  command -v ffmpeg >/dev/null || { echo "❌ 需要 ffmpeg（手机录的是 m4a，要转成 16k wav）"; exit 1; }
  mkdir -p "$ASR_DIR"
  if [ ! -x "$ASR_DIR/venv/bin/python3" ]; then
    echo "· 建 venv 并装 sherpa-onnx…"
    python3 -m venv "$ASR_DIR/venv"
    "$ASR_DIR/venv/bin/pip" install -q --disable-pip-version-check sherpa-onnx numpy
  fi
  if [ ! -f "$ASR_DIR/model/model.int8.onnx" ]; then
    echo "· 下模型（约 1GB，解开后只留 229MB 那个 int8 的）…"
    tmp="$(mktemp -d)"
    curl -fsSL --max-time 1800 -o "$tmp/m.tar.bz2" "$MODEL_URL"
    tar xf "$tmp/m.tar.bz2" -C "$tmp"
    rm -rf "$ASR_DIR/model"
    mv "$tmp"/sherpa-onnx-sense-voice-* "$ASR_DIR/model"
    # ⚠️ fp32 那份 895MB 用不上（int8 的精度实测一样），删掉
    rm -f "$ASR_DIR/model/model.onnx"
    rm -rf "$tmp"
  fi
  install -m 755 "$(cd "$(dirname "$0")" && pwd)/yxi-asr" "${HOME}/.local/bin/yxi-asr"
  echo "· 装好了：$(du -sh "$ASR_DIR" | cut -f1)"
  echo "  自测：yxi-asr $ASR_DIR/model/test_wavs/zh.wav"
  echo "  ⚠️ 第一次调用要 6~10 秒装载模型，之后走常驻守护（闲置 10 分钟自己退，常驻约 330MB）。"
  exit 0
fi

if [ "${1:-}" = "--publish" ]; then
  # ⚠️ **同一时刻只许一个发布在跑。**
  #    踩过：一个旧流程的发布进程还活着（传得慢，34MB 在 1Mbps 的链路上要五到八分钟），
  #    我又起了一个新的 —— 两个往同一个目录写，旧的那个用老顺序把清单翻到了
  #    一个还没传完的包上，手机立刻报「解析包时出现问题」。
  #    发布是**有副作用且不可交换**的操作，必须串行。见 TROUBLESHOOTING #159。
  exec 9>/tmp/.yxi-publish.lock
  if ! flock -n 9; then
    echo "❌ 已经有一个发布在跑了（34MB 在这条 1Mbps 的链路上要好几分钟）。"
    echo "   等它跑完，或者 kill 掉再来 —— 两个一起跑会把清单翻到半截包上。"
    exit 1
  fi
  APK="${2:?用法: $0 --publish <apk> <versionCode> <versionName> [说明]}"
  CODE="${3:?缺 versionCode}"; NAME="${4:?缺 versionName}"; NOTES="${5:-}"
  [ -f "$APK" ] || { echo "找不到 $APK"; exit 1; }
  mkdir -p "$EVENTS_DIR"
  install -m 644 "$APK" "$EVENTS_DIR/Yxi.apk"
  # ⚠️ **清单里的文件名带版本号**（`Yxi-78.apk`）。
  #    固定叫 Yxi.apk 的话，Cloudflare 会把那个 URL 缓存 4 小时 ——
  #    清单说有新版、下下来还是上一版，手机上表现为「更新了个寂寞」。
  #    带上版本号 = 每次发布都是一个 CDN 没见过的新 URL，结构上不可能拿到旧包。
  #    见 TROUBLESHOOTING #148。App 读的是清单里的 `file` 字段，所以老版本也能跟上。
  VER_APK="Yxi-${CODE}.apk"
  cp -f "$EVENTS_DIR/Yxi.apk" "$EVENTS_DIR/$VER_APK"
  python3 -c 'import json,sys,pathlib; pathlib.Path(sys.argv[1]).write_text(json.dumps({"versionCode":int(sys.argv[2]),"versionName":sys.argv[3],"file":sys.argv[5],"notes":sys.argv[4]},ensure_ascii=False,indent=1))' \
    "$EVENTS_DIR/latest.json" "$CODE" "$NAME" "$NOTES" "$VER_APK"
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
    # ⚠️⚠️ **顺序是硬要求：先传包 → 校验 → 最后才传清单。**
    #    原来三个文件一条 scp 一起传 —— 传输被打断（我就撞过一次：发布跑在
    #    有超时的后台任务里，2 分钟到点被杀）时**清单已经落地、APK 还是半截**，
    #    于是清单指向一个 12MB 的残包，手机上报「解析包时出现问题」。
    #    而 sha256 校验写在 scp 之后，进程被杀就根本没跑到。
    #    清单是**发布的开关**：它必须是最后一步，而且只在包验过之后才翻。
    #    见 TROUBLESHOOTING #159。
    # ⚠️ **用 rsync 不用 scp。** 到 hk13 的链路实测只有 **约 1 Mbps** ——
    #    34MB 要传五到八分钟，scp 一断就得从头再来（我连着被超时杀过三次）。
    #    rsync 的 `--append-verify` 能接着 `.part` 续传，而且相邻版本的 APK
    #    大部分内容是一样的，增量传输还能再省一大截。
    #    没有 rsync 就退回 scp（功能不受影响，只是慢）。
    if command -v rsync >/dev/null; then
      rsync --append-verify --partial -e ssh "$EVENTS_DIR/$VER_APK" "hk13:$DST/$VER_APK.part"
    else
      scp -q "$EVENTS_DIR/$VER_APK" "hk13:$DST/$VER_APK.part"
    fi
    HERE=$(sha256sum "$EVENTS_DIR/$VER_APK" | cut -d" " -f1)
    THERE=$(ssh hk13 "sha256sum $DST/$VER_APK.part" | cut -d" " -f1)
    if [ "$HERE" != "$THERE" ]; then
      echo "  ❌ 传过去的包对不上！本地 ${HERE:0:12}… ≠ 远端 ${THERE:0:12}…"
      ssh hk13 "rm -f $DST/$VER_APK.part"
      echo "     残件已删，清单没动（手机上还是上一个能用的版本）"
      exit 1
    fi
    # ⚠️ 传 `.part` 再改名：mv 在同一文件系统上是原子的，
    #    所以那个正式文件名要么不存在、要么就是完整的，不会有中间态。
    ssh hk13 "mv $DST/$VER_APK.part $DST/$VER_APK"
    if command -v rsync >/dev/null; then
      rsync --partial -e ssh "$EVENTS_DIR/Yxi.apk" "hk13:$DST/Yxi.apk.part"
    else
      scp -q "$EVENTS_DIR/Yxi.apk" "hk13:$DST/Yxi.apk.part"
    fi
    ssh hk13 "mv $DST/Yxi.apk.part $DST/Yxi.apk"
    echo "  · 包已同步，sha256 一致：${HERE:0:12}…"
    # 包验过了，现在才翻开关
    scp -q "$EVENTS_DIR/latest.json" "hk13:$DST/"

    # 下载页那个按钮也指到带版本号的文件上 —— 它原来写死 /Yxi.apk，
    # 同样会被 CDN 缓存住（实测新用户从页面下到的是三个版本前的包）。
    ssh hk13 "sed -i -E 's#href=\"/Yxi(-[0-9]+)?\.apk\"#href=\"/$VER_APK\"#g' /var/www/yxi/index.html 2>/dev/null || true"

    # ⚠️ **最后走一遍公网真下载，看 versionCode 对不对。**
    #    前面所有校验都是「源站上是对的」；用户走的是 CDN。
    #    #69 的教训是「传完不校验 = 没传」，#146 补一句：**校验源站不等于校验用户看到的**。
    if command -v curl >/dev/null && command -v aapt2 >/dev/null 2>&1 || [ -x /opt/android-sdk/build-tools/37.0.0/aapt2 ]; then
      AAPT=$(command -v aapt2 || echo /opt/android-sdk/build-tools/37.0.0/aapt2)
      TMPAPK=$(mktemp); BASE="https://yxi.keuury.com/$(cat "$TOKEN")"
      if curl -fsS --max-time 180 -o "$TMPAPK" "$BASE/$VER_APK"; then
        GOT=$("$AAPT" dump badging "$TMPAPK" 2>/dev/null | grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+')
        if [ "$GOT" = "$CODE" ]; then
          echo "  · 公网真下一遍：versionCode $GOT ✓"
        else
          echo "  ❌ 公网下到的是 versionCode $GOT，不是 $CODE —— 用户拿不到这一版！"
          echo "     多半是 CDN 缓存。查 cf-cache-status，或去 Cloudflare 清一下缓存。"
          rm -f "$TMPAPK"; exit 1
        fi
      else
        echo "  ⚠️ 公网下载没成功，没法确认用户能不能拿到 —— 手动试一下 $BASE/$VER_APK"
      fi
      rm -f "$TMPAPK"
    fi
    publish_server || true
  fi
  exit 0
fi

if [ "${1:-}" = "--publish-server" ]; then publish_server; exit $?; fi

if [ "${1:-}" = "--uninstall" ]; then uninstall; exit 0; fi

mkdir -p "$BIN_DIR" "$EVENTS_DIR"
install -m 755 "$HOOK_SRC" "$HOOK_DST"
[ -f "$HUB_SRC" ] && install -m 755 "$HUB_SRC" "$HUB_DST"
# 实验室接口：agent 给用户看/审东西只走它（yxi-lab spec 看契约）
LAB_SRC="$(cd "$(dirname "$0")" && pwd)/yxi-lab"; [ -f "$LAB_SRC" ] && install -m 755 "$LAB_SRC" "${BIN_DIR}/yxi-lab"
touch "$EVENTS"; chmod 600 "$EVENTS"
echo "· 装好 $HOOK_DST"

mkdir -p "$(dirname "$SETTINGS")"
[ -f "$SETTINGS" ] || echo '{}' > "$SETTINGS"
BAK="${SETTINGS}.yxi-bak-$(date +%s)"
cp "$SETTINGS" "$BAK"
echo "· 备份 $BAK"

python3 - "$SETTINGS" "$HOOK_DST" "$EVENTS_LIST" "$HUB_DST" <<'PY'
import json, sys, pathlib
settings, hook, events = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3].split()
hub = sys.argv[4]
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
    # 它的准确含义是「**Claude Code 不等这个钩子跑完**」（实测：钩子里 sleep 20，
    # 阻塞式那次 claude 退出时钩子已经跑完，async 那次还没跑完）。
    # 不等 = 钩子想说什么都是一场它多半会输的赛跑 —— 于是它**当不了决定者**。
    # 实测阻塞式 hook 返回 allow 时，**屏幕上一个提示都不曾出现** ——
    # 「自动放行」会变成一个 if 写错就发生、且事后查不出来的事；
    # 而且阻塞期间坐在键盘前的人**没有任何东西可以按**（桌面被废掉）。
    # 见 TROUBLESHOOTING #153。**别去掉这个 true。**
    g = {"hooks": [{"type": "command", "command": hook, "async": True}]}
    # ⚠️ `Notification` 混着 `idle_prompt`（空闲 60 秒的提醒，文案是
    # "Claude is waiting for your input"）—— 不加 matcher 的话，
    # 一个闲了一分钟的会话也会把手机叫醒。这是「手机白响」的来源之一。
    if ev == "Notification":
        g["matcher"] = "permission_prompt|agent_needs_input"
    groups.append(g)
    added.append(ev)
# SessionStart 注入「你在哪个组、同组有谁」。
#
# ⚠️ **为什么用钩子而不是写进 CLAUDE.md**：写文档的话成员名单是**静态**的，
#    一改分组就得改文档，而且新开的会话根本不知道自己有队友。
#    钩子是**每次会话开始时现算**的 —— 改组只要写 ~/.yxi/groups.json，别的什么都不用动。
# ⚠️ SessionStart 的 source 含 startup / resume / clear / **compact**，
#    所以 `/compact` 之后会再注入一次，名单不会因为上下文被压缩而丢。
# ⚠️ **这条不能 async**。async = Claude Code 不等它跑完，
#    而我们要的**就是**它的 stdout（那段注入上下文的 JSON）。
#    ⚠️ 实测提醒：钩子够快的话，async 那次**照样注入成功了** ——
#    所以这是个**赛跑**，不是稳定的失败。正因为它平时看着能用、偶尔悄悄丢，
#    才更要老老实实用阻塞式。`yxi-hub context` 要起两次 python3，不是没有输的可能。
#    这跟上面那条「yxi-hook 必须 async」不冲突：那条本来就不该说话。
# ⚠️ 没编进任何组时 `yxi-hub context` 什么都不输出，所以不在组里的会话零开销。
sg = hooks.setdefault("SessionStart", [])
if not any("yxi-hub" in str(h.get("command", "")) for g in sg for h in g.get("hooks", [])):
    sg.append({"hooks": [{"type": "command", "command": hub + " context"}]})
    added.append("SessionStart(分组)")

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
