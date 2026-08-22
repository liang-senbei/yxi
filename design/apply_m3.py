#!/usr/bin/env python3
"""方向 A → 方向 B（Material 3 深色）批量套用。
三件事：① 色调面分层替换调色板 ② 去掉 1px 边框（M3 用面的明度分层，不用描边）
③ 加大圆角与呼吸。圆角只抬 ≤16px 的（≥18 的多半是圆形，抬了会变形）。"""
import re, sys, pathlib

# 有上下文歧义的先换：#1a1917 既是页面底色，也是「铜色按钮上的文字色」
CTX = [
    ("color:#1a1917", "color:#4d2600"),
    ("stroke=\"#1a1917\"", "stroke=\"#4d2600\""),
]
MAP = [
    ("#131211", "#100e0b"),  # 终端底
    ("#1a1917", "#16130f"),  # 页面底
    ("#1e1c1a", "#1a1713"),
    ("#232120", "#1e1b17"),  # 卡片面
    ("#2b2826", "#221f1b"),  # 控件面
    ("#332f2b", "#2d2925"),  # 抬起面
    ("#2f2b27", "#221f1b"),
    ("#3a3633", "#2d2925"),
    ("#39352f", "#2d2925"),  # 残留的（作背景用时）
    ("#4a443d", "#383430"),
    ("#ece8e3", "#ebe1d9"),
    ("#c9c2ba", "#d0c4b8"),
    ("#9b938a", "#a89b8f"),
    ("#6d665f", "#8a7d72"),
    ("#8d857c", "#8a7d72"),
    ("#e08b57", "#ffb787"),  # primary
    ("#f0a473", "#ffd0ad"),
    ("#35b1a1", "#8fd8c6"),  # tertiary
    ("#d5a244", "#ffc46b"),  # 等你
    ("#dd8a76", "#ffb4a6"),
    ("#8fc47a", "#9ce0a8"),
    ("#33241f", "#6d3a10"),
    ("#22322a", "#1f3b2c"),
    ("#2f4a3a", "#1f3b2c"),
    ("#5c4536", "#8a5a2e"),
    ("#151413", "#100e0b"),
    ("#7fa8d8", "#a8c8ff"),
    ("#26302f", "#1f3b2c"),
    ("#241f1c", "#221f1b"),
    ("#141312", "#100e0b"),
    ("#1c1917", "#16130f"),
    ("#f2eee9", "#ebe1d9"),
    ("#221e1b", "#1e1b17"),
    ("#3d2320", "#3d2320"),
]
# 这些颜色当边框用时整条去掉；作左侧强调条 / 虚线框的保留
DROP_BORDER = r"border:1px solid #(?:2d2925|383430|221f1b|8a5a2e);\s*"

def bump(m):
    v = int(m.group(1))
    return f"border-radius:{ {8:14,9:16,10:18,11:20,12:22,13:24,14:28,16:28}.get(v, v) }px"

for f in sys.argv[1:]:
    p = pathlib.Path(f); s = p.read_text()
    for a, b in CTX: s = s.replace(a, b)
    for a, b in MAP: s = s.replace(a, b)
    s = re.sub(DROP_BORDER, "", s)
    s = re.sub(r"border-radius:(\d+)px", bump, s)
    # 间距与字号：正文抬到 15、标签 12.5、内边距放松
    s = s.replace("font-size:14px; line-height:1.5", "font-size:15px; line-height:1.6")
    s = s.replace("font-size:14px; line-height:1.62", "font-size:15px; line-height:1.7")
    s = s.replace("gap:16px; padding:18px 14px", "gap:22px; padding:16px 18px")
    s = s.replace("gap:18px; padding:18px 14px", "gap:22px; padding:16px 18px")
    s = s.replace("gap:20px; padding:16px 14px", "gap:24px; padding:16px 18px")
    p.write_text(s)
    print(f"  ✅ {p.name}")
