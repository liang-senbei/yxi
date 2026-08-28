#!/usr/bin/env bash
# 模拟器上 adb install 经常变成"全新安装"，App 数据被清空。
# 这个脚本把「授权公钥 + 写回 hosts.json」一把做完（见 TROUBLESHOOTING）。
set -e
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
PKG=app.yxi
dump(){ $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; }
tapText(){ dump; local P=$($ADB shell cat /sdcard/u.xml 2>/dev/null | python3 -c "
import sys,re
x=sys.stdin.read()
for m in re.finditer(r'text=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    print((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2); break
"); [ -n "$P" ] && $ADB shell input tap $P; sleep "${2:-3}"; }

$ADB shell am force-stop $PKG; sleep 1
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
tapText Allow 3 || true
if $ADB shell run-as $PKG cat files/hosts.json 2>/dev/null | grep -q hostname; then
  echo "hosts.json 还在，不用恢复"; exit 0
fi
tapText 主机; tapText 公钥
dump
PUB=$($ADB shell cat /sdcard/u.xml 2>/dev/null | python3 -c "
import sys,re,html
x=html.unescape(sys.stdin.read()); m=re.search(r'(ssh-ed25519 [A-Za-z0-9+/=]{60,})',x); print(m.group(1) if m else '')")
[ -z "$PUB" ] && { echo "读不到公钥"; exit 1; }
grep -qF "$PUB" ~/.ssh/authorized_keys 2>/dev/null || echo "$PUB yxi@android" >> ~/.ssh/authorized_keys
HK=$(awk '{print $2}' /etc/ssh/ssh_host_ed25519_key.pub)
B64=$(printf '%s' "[{\"id\":\"dev\",\"alias\":\"dev\",\"hostname\":\"10.0.2.2\",\"port\":22,\"username\":\"root\",\"useKey\":true,\"watch\":true,\"hostKey\":\"$HK\"}]" | base64 -w0)
$ADB shell "run-as $PKG sh -c 'echo $B64 | base64 -d > files/hosts.json'"
$ADB shell am force-stop $PKG; sleep 1
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 12
echo "已恢复"
