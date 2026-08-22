#!/usr/bin/env python3
"""把 design/*.dc.html 的画板抽出来，拼成一个可批注的单页。
每块画板的 <style> 作用域限死在自己的容器里 —— 各文件都有 .k /.f /.row 这类同名类，
不隔离会互相覆盖（Terminal 和 DPad 都定义了 .k）。"""
import re, json, pathlib

D = pathlib.Path(__file__).resolve().parent.parent
ORDER = json.loads((D / "canvas.json").read_text())["artboards"]

boards = []
for i, ab in enumerate(ORDER):
    src = (D / ab["file"]).read_text()
    style = "".join(re.findall(r"<style>(.*?)</style>", src, re.S))
    body = src.split("</helmet>", 1)[1].split("</x-dc>", 1)[0].strip()
    # 把 .cls{...} 前缀成 #b<i> .cls{...}，body{...} 换成容器自身
    scoped = re.sub(r"(^|\})\s*([^{}@]+)\{", lambda m: f"{m.group(1)}\n" + ",".join(
        f"#b{i} {s.strip()}" if s.strip() not in ("body", "*") else
        (f"#b{i}" if s.strip() == "body" else f"#b{i} *")
        for s in m.group(2).split(",")) + "{", style)
    boards.append({"id": f"b{i}", "title": ab.get("title", ab["file"]), "style": scoped, "body": body})

print(json.dumps(boards, ensure_ascii=False))
