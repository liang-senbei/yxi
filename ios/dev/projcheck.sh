#!/usr/bin/env bash
# 在**临时目录**里生成 Xcode 工程并核对几件必须成立的事。
#
# ⚠️ **一定要在 scratch 里跑，不能在 ios/ 里直接跑。** 两个原因：
#   ① 在仓库里跑 xcodegen 会**覆盖 `App/Info.plist`**（那份手写的带注释的清单），
#      哪怕它后面崩了也已经顶掉了；
#   ② 仓库里有 `.build/`（SwiftPM 产物），实测会让 xcodegen 段错误（signal 11）。
#      同一份 project.yml 复制到干净目录里跑就正常 —— 所以别据此以为是配置有问题。
set -u
cd "$(dirname "$0")/.." || exit 1
# `command -v` 找 PATH 里的；找不到再看那个手装的位置
if command -v xcodegen >/dev/null 2>&1; then XG=$(command -v xcodegen)
elif [ -x /root/.local/bin/xcodegen ]; then XG=/root/.local/bin/xcodegen
else echo "跳过：没有 xcodegen（装了才跑这项）"; exit 0; fi

S=$(mktemp -d)
trap 'rm -rf "$S"' EXIT
cp -r project.yml App Sources Tests "$S"/ || exit 1
( cd "$S" && "$XG" generate --spec project.yml >/dev/null 2>&1 ) || { echo "❌ xcodegen 生成失败"; exit 1; }

# ⚠️ **两端版本号必须一致。** 发安卓 0.9.28 时漏同步过 iOS（iOS 还停在 0.9.27），
# 用户在 Xcode 里看到旧版本号才发现。两个平台是同一个产品，版本号对不上
# 就没法讨论「你装的是哪一版」。
AND=../android/app/build.gradle.kts
if [ -f "$AND" ]; then
  A_NAME=$(grep -oE 'versionName = "[^"]+"' "$AND" | head -1 | cut -d'"' -f2)
  A_CODE=$(grep -oE 'versionCode = [0-9]+' "$AND" | head -1 | grep -oE '[0-9]+')
  I_NAME=$(grep -oE 'marketingVersion "[^"]+"' project.yml | head -1 | cut -d'"' -f2)
  I_CODE=$(grep -oE 'buildVersion "[0-9]+"' project.yml | head -1 | grep -oE '[0-9]+')
  if [ "$A_NAME" != "$I_NAME" ] || [ "$A_CODE" != "$I_CODE" ]; then
    echo "❌ 两端版本号对不上：安卓 $A_NAME($A_CODE) / iOS $I_NAME($I_CODE)"
    exit 1
  fi
fi

python3 - "$S" <<'PY'
import re, sys, pathlib
pb = pathlib.Path(sys.argv[1], "Yxi.xcodeproj/project.pbxproj").read_text()
bad = []
# ⚠️ 五个 bundle 版本必须一致：扩展跟宿主不符会被直接打回
cur = sorted(set(re.findall(r'CURRENT_PROJECT_VERSION = ([^;]+);', pb)))
mkt = sorted(set(re.findall(r'MARKETING_VERSION = ([^;]+);', pb)))
if len(cur) != 1: bad.append(f"CURRENT_PROJECT_VERSION 不一致：{cur}")
if len(mkt) != 1: bad.append(f"MARKETING_VERSION 不一致：{mkt}")
# 扩展必须真被嵌进 App，否则装上去等于没有
if "Embed Foundation Extensions" not in pb: bad.append("扩展没有被嵌进 App")
if pb.count("com.apple.product-type.app-extension") != 2: bad.append("appex target 数不对")
# entitlements / Info.plist 被当资源拷会撞 "Multiple commands produce"
if re.search(r'\.entitlements in Resources', pb): bad.append("entitlements 被当资源拷了")

# ⚠️ **Info.plist 里的版本号要单独查一遍。** 苹果判「扩展跟宿主版本不符」看的是
# plist 里的 CFBundleVersion，跟 build settings 的 CURRENT_PROJECT_VERSION 是两码事
# —— 只查后者的话，plist 那边写错照样漏过去（这条是自验时发现的）。
import plistlib
vers = {}
for f in pathlib.Path(sys.argv[1]).rglob("*.plist"):
    if "xcodeproj" in str(f): continue
    try: d = plistlib.loads(f.read_bytes())
    except Exception: continue
    if "CFBundleVersion" in d:
        # ⚠️ **按完整路径做键，不能用文件名** —— 三个 bundle 的清单都叫 `Info.plist`，
        # 用文件名的话字典只剩一条，永远比不出不一致（自验时就是这么漏掉的）。
        rel = str(f.relative_to(sys.argv[1]))
        vers[rel] = (d.get("CFBundleShortVersionString"), d.get("CFBundleVersion"))
if len(set(vers.values())) > 1:
    bad.append(f"各 bundle 的 Info.plist 版本号不一致：{vers}")
for b in bad: print("❌", b)
print("✅ 工程结构检查通过" if not bad else "")
sys.exit(1 if bad else 0)
PY
