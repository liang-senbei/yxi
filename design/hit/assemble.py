#!/usr/bin/env python3
"""把 design/hit/*.js 内联进 hit-lab.src.html → hit-lab.html（同 shatter/assemble.py）。"""
import pathlib, re, sys
here = pathlib.Path(__file__).resolve().parent
src = here.parent / "hit-lab.src.html"
out = here.parent / "hit-lab.html"
mods = sorted(p for p in here.glob("*.js"))
if not mods:
    sys.exit("design/hit/ 里一个 .js 都没有")
parts = []
for p in mods:
    body = p.read_text(encoding="utf-8")
    n = len(re.findall(r"window\.HITFX\.push\(", body))
    parts.append(f"<script>\n/* ── {p.name}：{n} 款 ── */\n{body}\n</script>")
    print(f"{p.name}: {n} 款")
out.write_text(src.read_text(encoding="utf-8").replace("<!-- HITFX_MODULES -->", "\n".join(parts)), encoding="utf-8")
print(f"→ {out.name}  {out.stat().st_size//1024} KB")
