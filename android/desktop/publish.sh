#!/usr/bin/env bash
# 把 CI（desktop.yml）打出来的 Velopack 发布目录 Releases/ 发到 hk13:/var/www/yxi/desktop/ → https://yxi.keuury.com/desktop/
# 用法：android/desktop/publish.sh <gh run id>     （run id 看 `gh run list -R liang-senbei/yxi -w desktop.yml`）
# 装了的客户端每 6 小时读同目录的 releases.win.json，比版本高就静默下载 nupkg（Update.kt）。
# ⚠️ 旧的 Yxi-1.0.0.msi 还在同一目录（老板还在用那个地址），这里只增不删。
set -euo pipefail
run=${1:?用法: publish.sh <gh run id> [expected commit sha]}
expected=${2:-$(git rev-parse HEAD)}
[[ "$expected" =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full frozen commit SHA.' >&2; exit 1; }
# A downloadable artifact alone is not release evidence (older CI uploaded before installation checks).
verified_sha=$(gh run view "$run" -R liang-senbei/yxi --json status,conclusion,jobs,workflowName,headSha --jq \
  'if .status == "completed" and .conclusion == "success" and .workflowName == "Desktop" and any(.jobs[]; .name == "Windows Setup.exe + jar" and .conclusion == "success" and any(.steps[]; (.name | startswith("真装真启动")) and .conclusion == "success") and any(.steps[]; .name == "Workbench core regression" and .conclusion == "success")) then .headSha else "" end')
if [ "$verified_sha" != "$expected" ]; then
  echo "拒绝发布：构建未成功完成，或缺少通过的工作台回归/安装后启动检查，或提交与冻结源码不一致。" >&2
  exit 1
fi
tmp=$(mktemp -d)
gh run download "$run" -R liang-senbei/yxi -n Yxi-windows -D "$tmp"
ls -la "$tmp/Releases"
# Review builds can carry the same packageVersion as production. Never replace an immutable version.
curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
  https://yxi.keuury.com/desktop/releases.win.json > "$tmp/published-releases.win.json"
python3 "$(dirname "$0")/validate_release.py" "$tmp/Releases" "$tmp/published-releases.win.json"
# --chmod=F644：rsync 会原样带过去本机的权限，0600 到了 nginx 就 403（TROUBLESHOOTING #318）
rsync -av --chmod=D755,F644 "$tmp/Releases/" hk13:/var/www/yxi/desktop/
# 公网真取一遍：要 200；404 = nginx 白名单（hk13 /etc/nginx/snippets/yxi-dl.conf 的 location /desktop/）没放行
for f in Yxi-win-Setup.exe releases.win.json; do echo -n "$f: "; curl -sI "https://yxi.keuury.com/desktop/$f" | head -1; done
rm -rf "$tmp"
