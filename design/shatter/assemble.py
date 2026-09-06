#!/usr/bin/env python3
"""把 design/shatter/*.js 内联进 shatter-lab.src.html → shatter-lab.html。

⚠️ 为什么内联：公网 /lab/ 那条 nginx 白名单把所有文件都当 text/html 发，
   外链 .js 的 MIME 会不对。一个文件也更好发给老板。
"""
import pathlib, re, sys
here = pathlib.Path(__file__).resolve().parent
src = here.parent / "shatter-lab.src.html"
out = here.parent / "shatter-lab.html"
mods = sorted(p for p in here.glob("*.js"))
if not mods:
    sys.exit("design/shatter/ 里一个 .js 都没有")
parts = []
for p in mods:
    body = p.read_text(encoding="utf-8")
    n = len(re.findall(r"window\.SHATTER\.push\(", body))
    parts.append(f"<script>\n/* ── {p.name}：{n} 个 ── */\n{body}\n</script>")
    print(f"{p.name}: {n} 个特效")
html = src.read_text(encoding="utf-8").replace("<!-- SHATTER_MODULES -->", "\n".join(parts))
out.write_text(html, encoding="utf-8")
print(f"→ {out.name}  {out.stat().st_size//1024} KB")
