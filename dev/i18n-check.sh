#!/usr/bin/env bash
# 扫源码里所有 t("…") / Tr.t("…")，跟 core 的 ui/En.kt 比对，找漏翻的。
#
# ⚠️ 为什么必须有这个脚本：翻译表用**中文原文当 key**，好处是调用点直接读，
# 代价是**没有编译期检查**。漏翻 / 改了中文没同步改 key，表现都是
# 「那句在英文界面下仍然是中文」—— 不报错、不崩，只能靠扫。
#
# ⚠️ **两个模块都要扫**（883e542 之后纯 Kotlin 拆到了 :core，En.kt 也搬过去了）。
# 只扫 app 的话，core 里的中文永远照不出来，而且是静默的。
# core 里翻译走 `Tr.t("…")`，所以那个写法也要认。
#
# ⚠️ 只查「漏」，不查「多」。表里多几条没用的无害（可能是刚改过文案的旧 key），
# 但也会顺手列出来，方便清。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)/android"
python3 - "$ROOT/app/src/main/kotlin/app/yxi" "$ROOT/core/src/main/kotlin/app/yxi" <<'PY'
import re,os,sys
SRCS=[d for d in sys.argv[1:] if os.path.isdir(d)]
assert SRCS, '一个源码目录都没找到 —— 是不是又搬家了？'

# ⚠️ 先去注释再扫：`Tr.kt` 的 KDoc 里写着 `t("中文")` 当例子，
#    不去掉就会被当成漏翻的一句，然后有人去 En.kt 加一条永远用不上的翻译。
def strip_comments(src):
    out=[]; i=0; n=len(src); q=None
    while i<n:
        c=src[i]
        if q:                                   # 字符串里：原样抄，认转义
            out.append(c)
            if c=='\\' and i+1<n: out.append(src[i+1]); i+=2; continue
            if c==q: q=None
            i+=1; continue
        if c=='"': q='"'; out.append(c); i+=1; continue
        if src.startswith('//',i):              # 行注释：吃到行尾
            j=src.find('\n',i); i=n if j<0 else j; continue
        if src.startswith('/*',i):              # 块注释：吃到 */
            j=src.find('*/',i+2); i=n if j<0 else j+2; continue
        out.append(c); i+=1
    return ''.join(out)

# `t("…")` 或 `Tr.t("…")`；前面是别的标识符/点号的（如 `fmt.t(`）不算
KEY=re.compile(r'(?:(?<![A-Za-z0-9_.])|(?<=\bTr\.))t\("((?:[^"\\]|\\.)*)"\)')

used={}
for SRC in SRCS:
    for root,_,fs in os.walk(SRC):
        for f in sorted(fs):
            if not f.endswith('.kt'): continue
            p=os.path.join(root,f)
            for m in KEY.finditer(strip_comments(open(p,encoding='utf-8').read())):
                used.setdefault(m.group(1), os.path.relpath(p,SRC))

EN=[os.path.join(d,'ui/En.kt') for d in SRCS if os.path.exists(os.path.join(d,'ui/En.kt'))]
assert len(EN)==1, 'En.kt 应该只有一份，实际 %d 份：%s' % (len(EN), EN)
en=open(EN[0],encoding='utf-8').read()
have=set(re.findall(r'^\s*"((?:[^"\\]|\\.)*)" to ', en, re.M))

missing=[(k,v) for k,v in used.items() if k not in have]
# ⚠️ 「多余」要放宽一点：enum 里是 `Sessions("会话")` + `get() = t(zh)`，
# 中文原文以**普通字面量**出现，扫 `t("…")` 看不见它。所以只要源码里
# 任何地方出现过这个字面量就算在用 —— 否则会误报，然后被人当真删掉，翻译静默失效。
alltext=''.join(open(os.path.join(r,f),encoding='utf-8').read()
                for SRC in SRCS for r,_,fs in os.walk(SRC) for f in fs if f.endswith('.kt'))
extra=[k for k in have if k not in used and ('"'+k+'"') not in alltext]

print("源码里 %d 句 · 表里 %d 条（扫了 %d 个模块）" % (len(used), len(have), len(SRCS)))
if missing:
    print("\n✗ 漏翻 %d 句（英文界面下会显示中文）：" % len(missing))
    for k,f in missing: print("   %-28s %s" % (f, k[:60]))
if extra:
    print("\n· 表里多余 %d 条（源码里已经没人用了，可以删）：" % len(extra))
    for k in extra[:20]: print("   %s" % k[:70])
if not missing: print("\n✓ 一句不漏")
sys.exit(1 if missing else 0)
PY
