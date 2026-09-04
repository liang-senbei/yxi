#!/usr/bin/env python3
# 把 design/STYLE.md 排成一页离线 HTML，推到实验室给用户在手机上读。
# ⚠️ 单文件、不引任何外部资源（实验室契约）。
import html as H
import pathlib
import re

SRC = pathlib.Path("/root/src/workspace/Yxi/design/STYLE.md")
OUT = pathlib.Path("/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/风格文档.html")

def inline(t):
    t = H.escape(t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    return t

lines = SRC.read_text(encoding="utf-8").split("\n")
out, i = [], 0
while i < len(lines):
    l = lines[i]
    if l.startswith("> "):                       # 引用块（项目里都是 ⚠️ 提醒）
        buf = []
        while i < len(lines) and lines[i].startswith(">"):
            buf.append(lines[i].lstrip("> ").rstrip()); i += 1
        out.append('<div class="warn">' + "<br>".join(inline(x) for x in buf) + "</div>"); continue
    if l.startswith("|"):                        # 表格
        rows = []
        while i < len(lines) and lines[i].startswith("|"):
            rows.append([c.strip() for c in lines[i].strip("|").split("|")]); i += 1
        head, body = rows[0], [r for r in rows[2:]]
        out.append("<div class='tw'><table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) +
                   "</tr></thead><tbody>" +
                   "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body) +
                   "</tbody></table></div>")
        continue
    if re.match(r"^\d+\. ", l) or l.startswith("- "):   # 列表
        ol = bool(re.match(r"^\d+\. ", l))
        items = []
        while i < len(lines) and (re.match(r"^\d+\. ", lines[i]) or lines[i].startswith("- ") or
                                  (lines[i].startswith("  ") and items)):
            cur = lines[i]
            if cur.startswith("  ") and items:            # 续行
                items[-1] += " " + cur.strip()
            else:
                items.append(re.sub(r"^(\d+\. |- )", "", cur))
            i += 1
        tag = "ol" if ol else "ul"
        out.append(f"<{tag}>" + "".join(f"<li>{inline(x)}</li>" for x in items) + f"</{tag}>")
        continue
    if l.startswith("#"):
        n = len(l) - len(l.lstrip("#"))
        out.append(f"<h{min(n,4)}>{inline(l.lstrip('# ').strip())}</h{min(n,4)}>"); i += 1; continue
    if l.strip() == "---":
        out.append("<hr>"); i += 1; continue
    if l.strip():
        buf = []
        while i < len(lines) and lines[i].strip() and not lines[i].startswith(("#", "|", "- ", "> ")) \
                and not re.match(r"^\d+\. ", lines[i]) and lines[i].strip() != "---":
            buf.append(lines[i].strip()); i += 1
        out.append("<p>" + inline(" ".join(buf)) + "</p>"); continue
    i += 1

body = "\n".join(out)
# 颜色样例条：文档里提到的那几种色，直接画出来比写十六进制好使
swatches = "".join(
    f'<div class="sw"><i style="background:{c}"></i><span>{n}</span></div>'
    for n, c in [("等你 Amber", "#E8A33D"), ("干活 Teal", "#2FA3A0"), ("刚跑完 Copper", "#C1743E"),
                 ("Pro", "linear-gradient(135deg,#346BF0,#8AB4F8)"),
                 ("Ultra", "linear-gradient(135deg,#FDBE5A,#F59E8C,#FFE1A8)"),
                 ("图标底 #F4F6FA", "#F4F6FA")]
)

OUT.write_text(f"""<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="yxi-aspect" content="3:4">
<title>Yxi 界面风格与做法</title>
<style>
  :root{{--bg:#F4F6FA;--ink:#1B1F24;--ink2:#5A6472;--line:#E2E7EF;--card:#FFFFFF;--accent:#4C8DF6}}
  @media (prefers-color-scheme:dark){{
    :root{{--bg:#111418;--ink:#E8EDF4;--ink2:#98A2B3;--line:#242A32;--card:#171B21}}
  }}
  *{{box-sizing:border-box}}
  html,body{{margin:0;background:var(--bg);color:var(--ink);
    font:15px/1.75 system-ui,-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;
    -webkit-text-size-adjust:100%}}
  main{{max-width:720px;margin:0 auto;padding:26px 18px 60px}}
  .cover{{padding:18px 0 6px}}
  .logo{{width:60px;height:60px;display:block;margin-bottom:10px}}
  h1{{font-size:26px;line-height:1.3;margin:.2em 0 .1em;letter-spacing:-.01em}}
  .sub{{color:var(--ink2);font-size:14px;margin:0 0 6px}}
  h2{{font-size:19px;margin:34px 0 10px;padding-top:14px;border-top:1px solid var(--line);letter-spacing:-.01em}}
  h3{{font-size:16px;margin:22px 0 6px;color:var(--accent)}}
  h4{{font-size:15px;margin:16px 0 4px}}
  p{{margin:.6em 0}}
  ul,ol{{margin:.5em 0 .5em 1.1em;padding:0}}
  li{{margin:.35em 0}}
  code{{font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;
    background:rgba(127,140,160,.14);padding:.1em .35em;border-radius:5px}}
  b{{font-weight:650}}
  hr{{display:none}}
  .warn{{background:rgba(232,163,61,.12);border-left:3px solid #E8A33D;
    padding:10px 12px;border-radius:0 10px 10px 0;margin:14px 0;font-size:14px;color:var(--ink)}}
  .tw{{overflow-x:auto;margin:14px 0;border:1px solid var(--line);border-radius:12px}}
  table{{border-collapse:collapse;width:100%;font-size:13.5px;min-width:420px}}
  th,td{{text-align:left;padding:9px 11px;border-bottom:1px solid var(--line);vertical-align:top}}
  th{{background:rgba(127,140,160,.09);font-weight:600;white-space:nowrap}}
  tr:last-child td{{border-bottom:0}}
  .sws{{display:flex;flex-wrap:wrap;gap:10px;margin:14px 0}}
  .sw{{display:flex;align-items:center;gap:7px;background:var(--card);border:1px solid var(--line);
    border-radius:999px;padding:5px 11px 5px 5px;font-size:12.5px;color:var(--ink2)}}
  .sw i{{width:20px;height:20px;border-radius:50%;display:block}}
  footer{{margin-top:34px;padding-top:14px;border-top:1px solid var(--line);
    color:var(--ink2);font-size:12.5px}}
</style></head>
<body><main>
<div class="cover">
  <img class="logo" src="{{LOGO}}" alt="">
</div>
<div class="sws">{swatches}</div>
{body}
<footer>写于 2026-09-04 · 跟着界面一起改，别让它过期。仓库里的正本：<code>design/STYLE.md</code></footer>
</main></body></html>
""", encoding="utf-8")
print("写好", OUT.stat().st_size // 1024, "KB")
