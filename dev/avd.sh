#!/usr/bin/env bash
# AVD 管理 —— 建 / 起 / 连。无 GUI 服务器上跑 Android 模拟器。
# 用法: avd.sh create | start | stop | shell | install <apk...>
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

AVD=${AVD:-yxi}
IMG="system-images;android-34;google_apis_playstore;x86_64"
# 走本机 sing-box(mixed inbound,HTTP+SOCKS 都收)。10.0.2.2 = 模拟器看到的宿主机。
# 为什么必须走:accounts.google.com 直连超时,不挂代理登不了 Google 账号。
PROXY=${PROXY:-http://10.0.2.2:1080}

case "${1:-}" in
create)
  echo no | avdmanager create avd -n "$AVD" -k "$IMG" -d pixel_6 --force
  # 提高分辨率下限 + 给足内存,不然 Play 商店卡
  C="$HOME/.android/avd/$AVD.avd/config.ini"
  sed -i 's/^hw.ramSize=.*/hw.ramSize=4096/' "$C" 2>/dev/null || echo "hw.ramSize=4096" >> "$C"
  echo "disk.dataPartition.size=8G" >> "$C"
  echo "✅ AVD '$AVD' 已建"
  ;;
start)
  # -no-window: 无 GUI 跑;想看画面用 scrcpy 或改 VNC 的 DISPLAY
  # -gpu swiftshader_indirect: 无显卡时的软件渲染(有 KVM 加速,CPU 侧不慢)
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -http-proxy "$PROXY" \
      -memory 4096 > /tmp/emulator.log 2>&1 &
  echo "启动中 pid=$! · 日志 /tmp/emulator.log"
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
  echo "✅ 已开机: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  ;;
stop)   adb emu kill 2>/dev/null || pkill -f "emulator.*$AVD" || true; echo "已停" ;;
shell)  shift; adb shell "$@" ;;
install) shift; adb install-multiple -r "$@" ;;
*) sed -n '2,4p' "$0"; exit 1 ;;
esac
