#!/usr/bin/env bash
# Yxi 一键装机 —— 给一台**什么都没装**的 Linux 服务器装上：
#   tmux · Claude Code · Codex · Yxi 服务器侧工具（yxi-hook / yxi-hub / yxi-lab + 钩子注册）
#
#   curl -fsSL https://yxi.keuury.com/bootstrap.sh | bash
#
# ⚠️ **不需要 Node.js。** Claude Code 走官方原生安装器（静态二进制），Codex 用 GitHub 发布的
#    musl 静态二进制 —— 客户的机器上有没有 node / npm 都无所谓，也不会去动系统的 node。
# ⚠️ **幂等**：装过的一律跳过，重复跑只是刷一遍状态。
# ⚠️ 只有「系统包」那一步要 root（或免密 sudo）；不是 root 就跳过并说明，其余照装（都在 $HOME 下）。
# ⚠️ 装完**不登录**：登录在手机上「配置 → 连接」里做（Claude Code 是网页给码粘回来，Codex 是设备码）。
#
# 环境变量：YXI_NO_CODEX=1 不装 Codex · YXI_NO_CLAUDE=1 不装 Claude Code · YXI_SITE 换服务器侧工具的下载源
set -uo pipefail

SITE="${YXI_SITE:-https://yxi.keuury.com}"
BIN="$HOME/.local/bin"
mkdir -p "$BIN"
export PATH="$BIN:$PATH"
FAIL=0
say()  { printf '\n▶ %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ⚠ %s\n' "$*"; }

# ── 1. 系统包：tmux（会话的载体）· curl（下东西）· git · python3（install.sh 改 settings.json 用）
SUDO=""
if [ "$(id -u)" != 0 ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then SUDO="sudo -n"; else SUDO="none"; fi
fi
need=""
for c in tmux curl git python3; do command -v "$c" >/dev/null 2>&1 || need="$need $c"; done
if [ -n "$need" ]; then
  say "装系统包：$need"
  if [ "$SUDO" = none ]; then
    warn "不是 root、也没有免密 sudo，系统包装不了。请管理员先装：$need"; FAIL=1
  elif command -v apt-get >/dev/null 2>&1; then
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1
    # shellcheck disable=SC2086
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $need ca-certificates >/dev/null 2>&1 || FAIL=1
  elif command -v dnf >/dev/null 2>&1; then $SUDO dnf install -y -q $need ca-certificates || FAIL=1
  elif command -v yum >/dev/null 2>&1; then $SUDO yum install -y -q $need ca-certificates || FAIL=1
  elif command -v apk >/dev/null 2>&1; then $SUDO apk add -q $need bash ca-certificates libgcc libstdc++ || FAIL=1
  elif command -v pacman >/dev/null 2>&1; then $SUDO pacman -Sy --noconfirm --needed -q $need || FAIL=1
  else warn "认不出这台机器的包管理器，请自己装：$need"; FAIL=1
  fi
  [ $FAIL = 0 ] && ok "系统包装好了" || warn "系统包没装全"
else
  ok "tmux / curl / git / python3 都在"
fi

# ── 2. Claude Code：官方原生安装器（装到 ~/.local/bin/claude，以后自己后台更新）
if [ "${YXI_NO_CLAUDE:-}" != 1 ]; then
  if command -v claude >/dev/null 2>&1; then
    ok "Claude Code 已装：$(claude --version 2>/dev/null | head -1)"
  else
    say "装 Claude Code（约 100MB）"
    if curl -fsSL https://claude.ai/install.sh | bash >/dev/null 2>&1 && command -v claude >/dev/null 2>&1; then
      ok "Claude Code $(claude --version 2>/dev/null | head -1)"
    else
      warn "Claude Code 没装成 —— 这台机器到 claude.ai 通吗？"; FAIL=1
    fi
  fi
fi

# ── 3. Codex：GitHub 发布的静态二进制（装到 ~/.local/bin/codex）
if [ "${YXI_NO_CODEX:-}" != 1 ]; then
  if command -v codex >/dev/null 2>&1; then
    ok "Codex 已装：$(codex --version 2>/dev/null)"
  else
    say "装 Codex（约 90MB）"
    case "$(uname -m)" in
      x86_64|amd64) A=x86_64 ;;
      aarch64|arm64) A=aarch64 ;;
      *) A="" ;;
    esac
    if [ -z "$A" ]; then
      warn "Codex 没有 $(uname -m) 架构的包，跳过"
    else
      T=$(mktemp -d)
      if curl -fsSL --retry 2 -o "$T/codex.tgz" \
           "https://github.com/openai/codex/releases/latest/download/codex-$A-unknown-linux-musl.tar.gz" \
         && tar xzf "$T/codex.tgz" -C "$T" \
         && install -m 755 "$T"/codex-*-unknown-linux-musl "$BIN/codex"; then
        ok "Codex $(codex --version 2>/dev/null)"
      else
        warn "Codex 没装成 —— 这台机器到 github.com 通吗？"; FAIL=1
      fi
      rm -rf "$T"
    fi
  fi
fi

# ── 4. PATH：以后开的 shell（tmux 里的登录 shell）要找得到 ~/.local/bin
for f in "$HOME/.profile" "$HOME/.bashrc" "$HOME/.zshrc"; do
  [ "$f" = "$HOME/.zshrc" ] && [ ! -f "$f" ] && continue
  grep -qs '\.local/bin' "$f" 2>/dev/null || printf '\n# yxi: claude / codex 装在这儿\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$f"
done

# ── 5. Yxi 服务器侧工具：钩子（手机响）· yxi-hub（组内通讯）· yxi-lab（实验室）
say "装 Yxi 服务器侧工具"
D="$HOME/.yxi/server"; mkdir -p "$D"
for f in install.sh yxi-hook yxi-hub yxi-lab; do
  curl -fsSL -o "$D/$f" "$SITE/server/$f" || { warn "下不到 $SITE/server/$f"; FAIL=1; }
done
chmod +x "$D"/* 2>/dev/null
if [ -s "$D/install.sh" ] && command -v python3 >/dev/null 2>&1; then
  bash "$D/install.sh" || { warn "install.sh 没跑完"; FAIL=1; }
else
  warn "install.sh 没跑（缺 python3 或没下载到）"; FAIL=1
fi

# ── 6. 汇报
say "结果"
command -v tmux   >/dev/null 2>&1 && ok "tmux $(tmux -V 2>/dev/null | cut -d' ' -f2)" || warn "tmux 没有 —— 没有它手机上看不到会话"
command -v claude >/dev/null 2>&1 && ok "claude $(claude --version 2>/dev/null | head -1)" || warn "claude 没有"
command -v codex  >/dev/null 2>&1 && ok "codex $(codex --version 2>/dev/null)" || warn "codex 没有"
echo
if [ $FAIL = 0 ]; then
  echo "装好了。下一步：手机上「配置 → 连接」里登录 Claude Code / Codex，然后回看板 ＋ 开会话。"
else
  echo "有几项没装成 —— 看上面 ⚠ 的那几行。再跑一遍只会补没装的。"
fi
exit $FAIL
