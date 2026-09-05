#!/usr/bin/env bash
# AVD 管理 —— 无 GUI 服务器上跑 Android 模拟器。用法: avd.sh create|start|stop|shot|install <apk...>
# ⚠️ 不要用 set -e：start 里的 until 轮询正常退出码可能非 0，会误杀脚本。
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
AVD=${AVD:-yxi}
IMG="system-images;android-34;google_apis_playstore;x86_64"
# ⚠️ 默认【不挂代理】。模拟器的 -http-proxy 会把**所有 TCP** 都塞进那个 HTTP 代理，
# 而 sing-box 处理不了长连的 SSH 隧道 —— 表现是握手和认证都成功、传几百字节后
# `SocketException: Connection reset`，极难定位（见 TROUBLESHOOTING #15）。
# 只有需要登 Google 账号时才 PROXY=http://10.0.2.2:1080 临时开。
PROXY=${PROXY:-}
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
  # ⚠️⚠️ **必须放进自己的 systemd scope，不能直接 nohup。**
  # 这台机器上整棵 tmux / claude 进程树都活在 `cloud-watchdog.service` 的 cgroup 里，
  # 而 `cloud-watchdog.timer` **每 15 秒触发一次**；每次那个 unit 停下来，systemd 就把
  # cgroup 里的「残留进程」清掉 —— 从会话里 nohup 起的模拟器正好算残留。
  # 表现是**模拟器每 15 秒左右必死**，而且日志干干净净（emulator.log 停在「Boot completed」、
  # logcat 里没有任何崩溃），查起来完全没有方向：像是「App 一进对话页就把模拟器带走」。
  # 见 TROUBLESHOOTING #263。`systemd-run --scope` 把它挪进独立 scope，watchdog 就管不着了。
  RUN=""; command -v systemd-run >/dev/null 2>&1 && RUN="systemd-run --scope --collect --quiet --unit=yxi-avd-$$"
  nohup $RUN emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-metrics \
      -gpu swiftshader_indirect -no-snapshot -skin 720x1280 \
      ${PROXY:+-http-proxy "$PROXY"} -memory 2048 -cores 4 > /tmp/emulator.log 2>&1 &
  adb wait-for-device
  n=0; until [ "$(adb shell getprop sys.boot_completed 2>/dev/null|tr -d '\r')" = "1" ] || [ $n -gt 90 ]; do sleep 4; n=$((n+1)); done
  echo "✅ 开机完成 Android $(adb shell getprop ro.build.version.release|tr -d '\r') · abilist=$(adb shell getprop ro.product.cpu.abilist|tr -d '\r')"
  ;;
stop)    adb emu kill 2>/dev/null; pkill -f 'qemu-sys[t]em' 2>/dev/null; echo "已停" ;;
shot)    adb exec-out screencap -p > "$SHOT" && echo "$SHOT" ;;
install) shift; adb install-multiple -r "$@" ;;
*) sed -n '2p' "$0"; exit 1 ;;
esac
