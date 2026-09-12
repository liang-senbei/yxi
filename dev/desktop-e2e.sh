#!/usr/bin/env bash
# 桌面版的「还起得来吗」冒烟：起一块自己的 Xvfb → 跑 uber jar → 等窗口 → 点几处 → 逐张截图 → 查异常。
#
# 用法： dev/desktop-e2e.sh [jar路径]     （不给就用 build 出来的最新那个 linux jar；没有就先 gradle 打一个）
# 产物： /tmp/yxi-e2e/*.png + run.log，退出码 0 = 窗口起来了且没有意外异常。
#
# 为什么要有这个：桌面版「编过 + 逻辑自查」漏得掉整类问题（#328：弹层被父布局排到屏幕外，
# 进了组合、状态也对，就是没有像素）。改了界面，合并前跑一次这个，至少证明它还起得来、还画得出。
#
# ⚠️ 环境前提见 TROUBLESHOOTING #327：**必须装带 AWT 的 JRE**（`apt-get install -y --no-install-recommends openjdk-17-jre`）——
#    默认那个是 headless 包，报的是「No X11 DISPLAY variable was set, or no headful library support」，**后半句才是真原因**。
# ⚠️ 这套环境**验不了最大化**（setExtendedState 之后 getExtendedState 回 0，带不带边框都一样，见 #327），
#    「最大化会不会盖住任务栏」只能真 Windows 上看。
# ⚠️ **各人用各人的 DISPLAY**（默认 :97）：:99 是全组共用的，两个人同时点鼠标会互相串。
# ⚠️ pkill 的模式**会匹配到你自己这条命令行**，所以下面一律锚成 `^java -jar`（#327 里那条，一天绊倒三个人）。
set -u
D=${YXI_DISPLAY:-:97}
OUT=${YXI_E2E_OUT:-$(mktemp -d /tmp/yxi-e2e.XXXXXX)}; mkdir -p "$OUT"
TEST_HOME=$(mktemp -d /tmp/yxi-e2e-home.XXXXXX)
app_pid=; wm_pid=; display_pid=
cleanup() {
  for pid in "$app_pid" "$wm_pid" "$display_pid"; do
    [ -z "$pid" ] || { kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; }
  done
}
trap cleanup EXIT
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
jar=${1:-}
if [ -z "$jar" ]; then
  jar=$(ls -t "$ROOT"/android/desktop/build/compose/jars/Yxi-linux-x64-*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { (cd "$ROOT/android" && ./gradlew -q :desktop:packageUberJarForCurrentOS) || exit 1
                     jar=$(ls -t "$ROOT"/android/desktop/build/compose/jars/Yxi-linux-x64-*.jar | head -1); }
fi
echo "· jar: $jar"
if [ -e "/tmp/.X11-unix/X${D#:}" ]; then echo "显示 $D 已占用，请设置独立 YXI_DISPLAY" >&2; exit 1; fi
Xvfb "$D" -screen 0 1400x900x24 >"$OUT/display.log" 2>&1 & display_pid=$!
sleep 2 2>/dev/null || DISPLAY=$D xdotool sleep 2
DISPLAY=$D xfwm4 >"$OUT/wm.log" 2>&1 & wm_pid=$!
DISPLAY=$D "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Duser.home="$TEST_HOME" -jar "$jar" > "$OUT/run.log" 2>&1 & app_pid=$!
ok=0
for i in $(seq 1 30); do
  DISPLAY=$D xdotool sleep 1
  DISPLAY=$D xdotool search --name "^Yxi$" >/dev/null 2>&1 && { ok=1; echo "· 窗口 ${i}s 起来了"; break; }
done
[ "$ok" = 1 ] || { echo "✗ 30 秒没等到窗口"; tail -20 "$OUT/run.log"; exit 1; }
DISPLAY=$D xdotool sleep 4
read -r wx wy ww wh <<<"$(DISPLAY=$D xdotool search --name '^Yxi$' getwindowgeometry --shell | sed -n 's/^\(X\|Y\|WIDTH\|HEIGHT\)=//p' | tr '\n' ' ')"
echo "· 窗口 ${ww}x${wh} @ $wx,$wy"
shot() { DISPLAY=$D import -window root "$OUT/$1.png" 2>/dev/null && echo "  截图 $OUT/$1.png"; }
tap() { DISPLAY=$D xdotool mousemove "$1" "$2" sleep 0.4 click 1 sleep 2; }
shot 1-启动
# 左栏底部那两个入口（坐标跟着窗口走；布局变了要跟着改，这里只求「点了有反应」）
tap $((wx+55))  $((wy+wh-75)); shot 2-配置
tap $((wx+193)) $((wy+wh-75)); shot 3-我的
bad=$(grep -iE "exception|error" "$OUT/run.log" | grep -viE "Cannot create Linux GL context|Fallback to next API" | head -5)
if [ -n "$bad" ]; then echo "✗ 日志里有意外的异常："; echo "$bad"; exit 1; fi
echo "✓ 起得来、点得动、日志干净（除了 Skia 回落软件渲染那两句，那是正常的）"
