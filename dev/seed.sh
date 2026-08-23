#!/usr/bin/env bash
# 模拟器上每次 `adb install -r` 都会清掉 App 数据（主机列表、密钥全没）。
# 这个脚本把「重装后能立刻接着测」需要的一切一次做完：
#   ① 逼 App 生成密钥并从 logcat 取出公钥
#   ② 把公钥装进本机和 station 的 authorized_keys（替换旧的那行）
#   ③ 用 ssh-keyscan 拿两台的主机指纹，直接写进 hosts.json（省掉手点「指纹对得上」）
#   ④ 重启 App
# 见 TROUBLESHOOTING #31。
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$ANDROID_HOME/platform-tools:$PATH"
WATCH="${1:-true}"      # hosts.json 里 watch 字段的值

# ⚠️ **仓库根目录用脚本自己的位置算，绝不用相对路径。**
# 我已经因为「`cd android` 之后 `adb install -r android/app/...`」白查过两次
# （见 TROUBLESHOOTING #43，写完那条二十分钟后又踩了一遍）。
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 可以用 YXI_APK 指定装哪个包（测自更新时要先装旧版）
APK="${YXI_APK:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"

# 顺手把装包也做了 —— 装包失败静默跑旧包是这个循环里最贵的一种错
if [ -f "$APK" ]; then
  adb install -r "$APK" | tail -1     # ⚠️ 别把输出吞掉，就一行
fi

# ⚠️ 授权要在**第一次启动之前**：重装会撤销权限，首次启动时系统的授权对话框会
# 盖住整个界面，后面那个「点公钥按钮」的坐标就点在对话框的遮罩上了 ——
# 表现是「拿不到公钥」，而真正的原因跟公钥一点关系都没有。
adb shell pm grant app.yxi android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 取 App 的公钥。
# ⚠️ 不再靠「点公钥按钮」—— 那个入口搬进设置页了，追坐标太脆。
# 现在是：先放一份 hosts.json 让它去连，连接会用到私钥 → 没有就现生成 → 生成时打日志。
adb shell am force-stop app.yxi
adb shell am start -n app.yxi/.MainActivity >/dev/null; sleep 4      # 让它把 files/ 建出来
adb logcat -c
printf '[{"id":"seed","alias":"seed","hostname":"10.0.2.2","port":22,"username":"root","useKey":true}]' > /tmp/.seed.json
adb push /tmp/.seed.json /data/local/tmp/hosts.json >/dev/null
adb shell run-as app.yxi cp /data/local/tmp/hosts.json files/hosts.json
adb shell am force-stop app.yxi; adb shell am start -n app.yxi/.MainActivity >/dev/null; sleep 8
PUB=$(adb logcat -d -s YxiKey | grep -o 'pub=[A-Za-z0-9+/=]*' | tail -1 | cut -d= -f2- || true)
[ -n "$PUB" ] || { echo "✗ 没从 logcat 里拿到公钥"; exit 1; }
echo "· 公钥 ${PUB:0:24}…"

auth() {  # $1 = "" 表示本机
  if [ -z "$1" ]; then
    grep -v yxi@android ~/.ssh/authorized_keys > /tmp/.ak && echo "ssh-ed25519 $PUB yxi@android" >> /tmp/.ak
    cat /tmp/.ak > ~/.ssh/authorized_keys; rm -f /tmp/.ak
  else
    ssh "$1" "grep -v yxi@android ~/.ssh/authorized_keys > /tmp/.ak; echo 'ssh-ed25519 $PUB yxi@android' >> /tmp/.ak; cat /tmp/.ak > ~/.ssh/authorized_keys; rm -f /tmp/.ak"
  fi
}
auth ""; echo "· 本机 authorized_keys 更新"
auth station; echo "· station authorized_keys 更新"

HKD=$(ssh-keyscan -t ed25519 127.0.0.1 2>/dev/null | awk '{print $3}' | head -1)
HKS=$(ssh-keyscan -t ed25519 38.244.50.31 2>/dev/null | awk '{print $3}' | head -1)
cat > /tmp/.hosts.json <<EOF
[{"id":"dev","alias":"dev","hostname":"10.0.2.2","port":22,"username":"root","useKey":true,"watch":$WATCH,"hostKey":"$HKD"},
 {"id":"station","alias":"station","hostname":"38.244.50.31","port":22,"username":"root","useKey":true,"watch":$WATCH,"hostKey":"$HKS"}]
EOF
adb push /tmp/.hosts.json /data/local/tmp/hosts.json >/dev/null
adb shell run-as app.yxi cp /data/local/tmp/hosts.json files/hosts.json
rm -f /tmp/.hosts.json
echo "· hosts.json 写好（watch=$WATCH，指纹已预置，不会弹确认）"

adb shell am force-stop app.yxi; adb shell am start -n app.yxi/.MainActivity >/dev/null
echo "· App 重启完毕"
