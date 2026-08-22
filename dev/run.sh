#!/usr/bin/env bash
# 一条命令：保证模拟器在 → 构建 → 装 → 起 → 抓日志 → 截图。
# ⚠️ 别用 set -e：等待循环的中间退出码非 0 会误杀脚本（TROUBLESHOOTING #7）。
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
ROOT=/root/src/workspace/Yxi
SHOT=${SHOT:-/tmp/yxi-shot.png}
PKG=app.yxi

ensure_emu() {
  adb shell echo ok >/dev/null 2>&1 && return 0
  echo "· 模拟器不在，拉起…"
  "$ROOT/dev/avd.sh" start >/dev/null 2>&1
  local n=0
  until adb shell echo ok >/dev/null 2>&1 || [ $n -gt 40 ]; do sleep 4; n=$((n+1)); done
  adb shell echo ok >/dev/null 2>&1 && echo "· 模拟器就绪" || { echo "✗ 模拟器起不来"; return 1; }
}

case "${1:-all}" in
build)  cd "$ROOT/android" && ./gradlew :app:assembleDebug -q 2>&1 | grep -vE '^\s*$|^w:' | tail -12 ;;
all)
  # 先构建再拉模拟器：gradle 的 JVM 和模拟器抢内存，同时在会把模拟器挤掉
  echo "· 构建…"
  ( cd "$ROOT/android" && ./gradlew :app:assembleDebug -q 2>&1 | grep -vE '^\s*$|^w:' | tail -12 ) || exit 1
  ( cd "$ROOT/android" && ./gradlew --stop >/dev/null 2>&1 )   # 放掉 gradle 守护的内存
  ensure_emu || exit 1
  adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1
  # 测试私钥（G3 会换成 App 内生成 + Keystore）
  if [ -f /root/.yxi/g2-ed ]; then
    adb push /root/.yxi/g2-ed /data/local/tmp/k >/dev/null 2>&1
    adb shell "run-as $PKG sh -c 'cat /data/local/tmp/k > files/g2-key; chmod 600 files/g2-key'" >/dev/null 2>&1
  fi
  adb shell am force-stop $PKG; adb logcat -c
  adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
  sleep "${WAIT:-13}"
  adb exec-out screencap -p > "$SHOT" 2>/dev/null && echo "· 截图 $SHOT ($(stat -c%s "$SHOT") 字节)"
  echo "· pid: $(adb shell pidof $PKG | tr -d '\r')"
  ;;
log)    shift; adb logcat -d -s "${1:-YxiSSH}" 2>/dev/null | sed 's/^.*: //' | tail -"${2:-30}" ;;
shot)   adb exec-out screencap -p > "$SHOT" && echo "$SHOT" ;;
*) sed -n '2,3p' "$0" ;;
esac
