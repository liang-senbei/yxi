#!/usr/bin/env python3
"""把 design/swipe/*.js 内联进 swipe-lab.src.html → swipe-lab.html（同 shatter/assemble.py）。"""
import pathlib, re, sys
here = pathlib.Path(__file__).resolve().parent
src = here.parent / "swipe-lab.src.html"
out = here.parent / "swipe-lab.html"
mods = sorted(p for p in here.glob("*.js"))
if not mods:
    sys.exit("design/swipe/ 里一个 .js 都没有")
parts = []
for p in mods:
    body = p.read_text(encoding="utf-8")
    n = len(re.findall(r"window\.SWIPE\.push\(", body))
    parts.append(f"<script>\n/* ── {p.name}：{n} 款 ── */\n{body}\n</script>")
    print(f"{p.name}: {n} 款")
out.write_text(src.read_text(encoding="utf-8").replace("<!-- SWIPE_MODULES -->", "\n".join(parts)), encoding="utf-8")
print(f"→ {out.name}  {out.stat().st_size//1024} KB")
