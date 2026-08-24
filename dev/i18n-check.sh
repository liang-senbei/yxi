#!/usr/bin/env bash
# 扫源码里所有 t("…")，跟 ui/En.kt 的表比对，找漏翻的。
#
# ⚠️ 为什么必须有这个脚本：翻译表用**中文原文当 key**，好处是调用点直接读，
# 代价是**没有编译期检查**。漏翻 / 改了中文没同步改 key，表现都是
# 「那句在英文界面下仍然是中文」—— 不报错、不崩，只能靠扫。
#
# ⚠️ 只查「漏」，不查「多」。表里多几条没用的无害（可能是刚改过文案的旧 key），
# 但也会顺手列出来，方便清。
set -u
SRC="$(cd "$(dirname "$0")/.." && pwd)/android/app/src/main/kotlin/app/yxi"
python3 - "$SRC" <<'PY'
import re,os,sys
SRC=sys.argv[1]
KEY=re.compile(r'(?<![A-Za-z0-9_.])t\("((?:[^"\\]|\\.)*)"\)')
used={}
for root,_,fs in os.walk(SRC):
    for f in sorted(fs):
        if not f.endswith('.kt'): continue
        p=os.path.join(root,f)
        for m in KEY.finditer(open(p,encoding='utf-8').read()):
            used.setdefault(m.group(1), os.path.relpath(p,SRC))
en=open(os.path.join(SRC,'ui/En.kt'),encoding='utf-8').read()
have=set(re.findall(r'^\s*"((?:[^"\\]|\\.)*)" to ', en, re.M))
missing=[(k,v) for k,v in used.items() if k not in have]
# ⚠️ 「多余」要放宽一点：enum 里是 `Sessions("会话")` + `get() = t(zh)`，
# 中文原文以**普通字面量**出现，扫 `t("…")` 看不见它。所以只要源码里
# 任何地方出现过这个字面量就算在用 —— 否则会误报，然后被人当真删掉，翻译静默失效。
alltext=''.join(open(os.path.join(r,f),encoding='utf-8').read()
                for r,_,fs in os.walk(SRC) for f in fs if f.endswith('.kt'))
extra=[k for k in have if k not in used and ('"'+k+'"') not in alltext]
print("源码里 %d 句 · 表里 %d 条" % (len(used), len(have)))
if missing:
    print("\n✗ 漏翻 %d 句（英文界面下会显示中文）：" % len(missing))
    for k,f in missing: print("   %-28s %s" % (f, k[:60]))
if extra:
    print("\n· 表里多余 %d 条（源码里已经没人用了，可以删）：" % len(extra))
    for k in extra[:20]: print("   %s" % k[:70])
if not missing: print("\n✓ 一句不漏")
sys.exit(1 if missing else 0)
PY
