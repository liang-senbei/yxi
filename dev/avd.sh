#!/usr/bin/env bash
# AVD 管理 —— 无 GUI 服务器上跑 Android 模拟器。用法: avd.sh create|start|stop|shot|install <apk...>
# ⚠️ 不要用 set -e：start 里的 until 轮询正常退出码可能非 0，会误杀脚本。
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
AVD=${AVD:-yxi}
IMG="system-images;android-34;google_apis_playstore;x86_64"
# 走本机 sing-box（inbound 是 mixed，HTTP+SOCKS 都收）。10.0.2.2 = 模拟器眼里的宿主机。
# 必须挂：accounts.google.com 直连超时。
PROXY=${PROXY:-http://10.0.2.2:1080}
SHOT=${SHOT:-/tmp/avd-shot.png}

case "${1:-}" in
create)
  echo no | avdmanager create avd -n "$AVD" -k "$IMG" -d pixel_6 --force
  C="$HOME/.android/avd/$AVD.avd/config.ini"
  # 720x1280 而不是 1080x2400：软件渲染下大分辨率会触发 gfxstream 崩溃
  #   （ERROR | Failed to find ColorBuffer: NN），见 TROUBLESHOOTING #5
  sed -i 's/^hw.lcd.width=.*/hw.lcd.width=720/;s/^hw.lcd.height=.*/hw.lcd.height=1280/;s/^hw.lcd.density=.*/hw.lcd.density=320/' "$C"
  sed -i 's/^hw.ramSize=.*/hw.ramSize=2048/' "$C"
  echo "✅ AVD '$AVD' 已建（720x1280）"
  ;;
start)
  # ⚠️ pkill 的模式必须加方括号，否则 -f 会匹配到执行它的这条命令自身 → 自杀
  pkill -f 'qemu-sys[t]em' 2>/dev/null; sleep 2; adb kill-server >/dev/null 2>&1
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-metrics \
      -gpu swiftshader_indirect -no-snapshot -skin 720x1280 \
      -http-proxy "$PROXY" -memory 2048 -cores 4 > /tmp/emulator.log 2>&1 &
  adb wait-for-device
  n=0; until [ "$(adb shell getprop sys.boot_completed 2>/dev/null|tr -d '\r')" = "1" ] || [ $n -gt 90 ]; do sleep 4; n=$((n+1)); done
  echo "✅ 开机完成 Android $(adb shell getprop ro.build.version.release|tr -d '\r') · abilist=$(adb shell getprop ro.product.cpu.abilist|tr -d '\r')"
  ;;
stop)    adb emu kill 2>/dev/null; pkill -f 'qemu-sys[t]em' 2>/dev/null; echo "已停" ;;
shot)    adb exec-out screencap -p > "$SHOT" && echo "$SHOT" ;;
install) shift; adb install-multiple -r "$@" ;;
*) sed -n '2p' "$0"; exit 1 ;;
esac
