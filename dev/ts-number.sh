#!/usr/bin/env bash
# TROUBLESHOOTING.md 的编号体检：撞号 / 断号 / 下一个空号。
#
# ⚠️ 为什么需要它：编号是**靠肉眼扫 5800 行的尾巴**挑的，而这份文件四个 agent 同时在追加。
# 2026-09-07 一天撞了三次（#311 两次、#316 一次），每次都是「我写的时候还没看见你那条」——
# 不是谁不认真，是这个挑法本身必然撞（同 #317 的说法：口径/位置让人看不见）。
#
# 用法：写新条目**之前**跑一次，用它报的「下一个空号」。
#   dev/ts-number.sh
set -u
F="$(cd "$(dirname "$0")/.." && pwd)/TROUBLESHOOTING.md"
python3 - "$F" <<'PY'
import re, sys, collections
p = sys.argv[1]
nums = []
for i, line in enumerate(open(p, encoding='utf-8'), 1):
    # ⚠️ 两种写法都要认：早期是 `## 12. 标题`，后来改成 `## #311 标题`。
    # 只认新写法的话，前 134 条全看不见 —— 断号报一大串假的，撞号也可能漏。
    m = re.match(r'^##\s*#?(\d+)[.\s]', line)
    if m:
        nums.append((int(m.group(1)), i, line.strip()[:70]))

dup = [n for n, c in collections.Counter(n for n, _, _ in nums).items() if c > 1]
have = {n for n, _, _ in nums}
top = max(have) if have else 0
gaps = [n for n in range(1, top) if n not in have]

print("共 %d 条 · 最大 #%d" % (len(nums), top))
if dup:
    print("\n✗ 撞号 %d 个：" % len(dup))
    for d in sorted(dup):
        for n, i, t in nums:
            if n == d:
                print("   %s:%d  %s" % (p.rsplit('/', 1)[-1], i, t))
if gaps:
    print("\n· 断号（可能是谁改号时留下的洞，不一定是错）：%s" % ", ".join("#%d" % g for g in gaps))
print("\n→ 下一个空号：#%d" % (top + 1))
sys.exit(1 if dup else 0)
PY
