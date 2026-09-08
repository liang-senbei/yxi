#!/usr/bin/env bash
# 把 CI（desktop.yml）打出来的 Velopack 发布目录 Releases/ 发到 hk13:/var/www/yxi/desktop/ → https://yxi.keuury.com/desktop/
# 用法：android/desktop/publish.sh <gh run id>     （run id 看 `gh run list -R liang-senbei/yxi -w desktop.yml`）
# 装了的客户端每 6 小时读同目录的 releases.win.json，比版本高就静默下载 nupkg（Update.kt）。
# ⚠️ 旧的 Yxi-1.0.0.msi 还在同一目录（老板还在用那个地址），这里只增不删。
set -euo pipefail
run=${1:?用法: publish.sh <gh run id>}
tmp=$(mktemp -d)
gh run download "$run" -R liang-senbei/yxi -n Yxi-windows -D "$tmp"
ls -la "$tmp/Releases"
# --chmod=F644：rsync 会原样带过去本机的权限，0600 到了 nginx 就 403（TROUBLESHOOTING #318）
rsync -av --chmod=D755,F644 "$tmp/Releases/" hk13:/var/www/yxi/desktop/
# 公网真取一遍：要 200；404 = nginx 白名单（hk13 /etc/nginx/snippets/yxi-dl.conf 的 location /desktop/）没放行
for f in Yxi-win-Setup.exe releases.win.json; do echo -n "$f: "; curl -sI "https://yxi.keuury.com/desktop/$f" | head -1; done
rm -rf "$tmp"
