#!/usr/bin/env python3
"""rhythm-bench.src.html + 老板拍板要用的特效模块 → rhythm-bench.html（一个文件，给 /lab/ 用）。"""
import pathlib, re
here = pathlib.Path(__file__).resolve().parent
mods = sorted(here.glob("shatter/*.js")) + sorted(here.glob("swipe/*.js"))
parts = [f"<script>\n/* ── {p.relative_to(here)} ── */\n{p.read_text(encoding='utf-8')}\n</script>" for p in mods]
src = (here/"rhythm-bench.src.html").read_text(encoding="utf-8")
out = here/"rhythm-bench.html"
out.write_text(src.replace("<!-- FX_MODULES -->", "\n".join(parts)), encoding="utf-8")
print(f"内联 {len(mods)} 个模块 → {out.name} {out.stat().st_size//1024} KB")
