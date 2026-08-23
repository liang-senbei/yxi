#!/usr/bin/env python3
"""Yxi 视觉稿批注页 —— 点任意位置钉一条批注，落 pins.json，Claude 直接读文件。
只读画板 + 一个写 pins 的接口；不碰文件系统其它部分、不执行任何东西。
token 在路径里，够一个没有敏感内容的评审页用。"""
import json, os, re, pathlib, shutil, subprocess, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = pathlib.Path(__file__).resolve().parent
PINS = HERE / "pins.json"
TOKEN = (HERE / ".token").read_text().strip()
LOCK = threading.Lock()
PORT = int(os.environ.get("PORT", 8899))


def load_pins():
    try:
        return json.loads(PINS.read_text())
    except Exception:
        return []


def page():
    boards = json.loads(subprocess.run(
        ["python3", str(HERE / "build.py")], capture_output=True, text=True, check=True).stdout)
    css = "\n".join(b["style"] for b in boards)
    html = "\n".join(
        f'<section class="wrap"><h2>{i+1}. {b["title"]}</h2>'
        f'<div class="stage" data-board="{b["id"]}" data-title="{b["title"]}">'
        f'<div id="{b["id"]}" class="board">{b["body"]}</div>'
        f'<div class="pins"></div></div></section>'
        for i, b in enumerate(boards))
    return TPL.replace("/*BOARDCSS*/", css).replace("<!--BOARDS-->", html).replace("__TOKEN__", TOKEN)


TPL = r"""<!doctype html><html lang="zh"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Yxi 视觉稿 · 批注</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
/*BOARDCSS*/
html,body{margin:0;background:#111010;color:#ece8e3;font-family:'Space Grotesk',system-ui,sans-serif;-webkit-text-size-adjust:100%}
header{position:sticky;top:0;z-index:50;background:#1a1917;border-bottom:1px solid #39352f;padding:12px 16px;display:flex;align-items:center;gap:10px}
header b{font-size:16px;letter-spacing:-.01em;flex-grow:1}
.count{font-family:'JetBrains Mono',monospace;font-size:12px;color:#e08b57}
.hint{padding:14px 16px;font-size:13px;line-height:1.6;color:#9b938a;border-bottom:1px solid #2a2724}
.wrap{padding:22px 0 12px;display:flex;flex-direction:column;align-items:center;gap:10px}
h2{margin:0;font-size:13px;font-weight:600;color:#9b938a;letter-spacing:.04em}
.stage{position:relative;width:390px;flex-shrink:0}
.board{width:390px;height:844px;overflow:hidden;border:1px solid #39352f;border-radius:14px}
.pins{position:absolute;inset:0}
.pin{position:absolute;width:26px;height:26px;margin:-13px 0 0 -13px;border-radius:13px;background:#e08b57;color:#1a1917;
 font-size:13px;font-weight:700;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 3px rgba(224,139,87,.28);cursor:pointer}
.pin.done{background:#4a443d;color:#9b938a;box-shadow:0 0 0 3px rgba(74,68,61,.3)}
#sheet{position:fixed;left:0;right:0;bottom:0;z-index:100;background:#232120;border-top:1px solid #4a443d;
 padding:16px 16px 24px;transform:translateY(105%);transition:transform .18s ease}
#sheet.on{transform:none}
#sheet .where{font-family:'JetBrains Mono',monospace;font-size:11px;color:#6d665f;margin-bottom:9px}
#sheet textarea{width:100%;height:82px;background:#1a1917;border:1px solid #39352f;border-radius:10px;color:#ece8e3;
 font:14px/1.5 'Space Grotesk',system-ui;padding:11px;resize:none;outline:none}
#sheet textarea:focus{border-color:#e08b57}
.row{display:flex;gap:9px;margin-top:11px}
.btn{flex-grow:1;height:46px;border-radius:11px;border:1px solid #4a443d;background:none;color:#ece8e3;font:600 14px 'Space Grotesk',system-ui}
.btn.p{background:#e08b57;border-color:#e08b57;color:#1a1917}
.btn.d{flex-grow:0;width:64px;color:#dd8a76}
#toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%) translateY(20px);z-index:200;
 background:#ffb787;color:#4d2600;font-weight:600;font-size:13.5px;padding:11px 20px;border-radius:100px;
 opacity:0;pointer-events:none;transition:opacity .2s,transform .2s}
#toast.on{opacity:1;transform:translateX(-50%) translateY(0)}
.stage{touch-action:pan-y}
footer{padding:26px 16px 40px;text-align:center;font-family:'JetBrains Mono',monospace;font-size:11px;color:#4a443d}
</style></head><body>
<header><b>Yxi 视觉稿</b><span class="count" id="cnt">0 条批注</span></header>
<div class="hint">在任意画板上<b style="color:#e08b57">点一下</b>就钉一条批注 —— 写清楚哪里不对。点已有的钉子可以改或删。<br>我直接读这些批注,不用你描述位置。</div>
<!--BOARDS-->
<footer>pins.json · Claude 直接读</footer>
<div id="toast"></div>
<div id="sheet"><div class="where" id="where"></div>
<textarea id="txt" placeholder="这里怎么不对？"></textarea>
<div class="row"><button class="btn p" id="ok">保存</button><button class="btn" id="cancel">取消</button><button class="btn d" id="del">删除</button></div></div>
<script>
const T="__TOKEN__", api=p=>`/${T}/${p}`;
let pins=[], cur=null;
const $=s=>document.querySelector(s), sheet=$("#sheet");
async function load(){ pins=await (await fetch(api("pins"))).json(); render(); }
async function save(){ await fetch(api("pins"),{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(pins)}); render(); }
function render(){
  $("#cnt").textContent = pins.length+" 条批注";
  document.querySelectorAll(".stage").forEach(st=>{
    const b=st.dataset.board, layer=st.querySelector(".pins"); layer.innerHTML="";
    pins.forEach((p,i)=>{ if(p.board!==b) return;
      const el=document.createElement("div");
      el.className="pin"+(p.done?" done":""); el.textContent=i+1;
      el.style.left=p.x+"%"; el.style.top=p.y+"%";
      el.onclick=e=>{e.stopPropagation(); open(i);};
      layer.appendChild(el); });
  });
}
function open(i){ cur=i; const p=pins[i];
  $("#where").textContent=`#${i+1} · ${p.title} · ${p.x.toFixed(1)}% , ${p.y.toFixed(1)}%`;
  $("#txt").value=p.text||""; $("#del").style.display=p.isNew?"none":"";
  sheet.classList.add("on"); $("#txt").focus(); }
document.querySelectorAll(".stage").forEach(st=>{
  st.addEventListener("click",e=>{
    if(e.target.classList.contains("pin")) return;
    const r=st.getBoundingClientRect();
    pins.push({board:st.dataset.board,title:st.dataset.title,
      x:(e.clientX-r.left)/r.width*100, y:(e.clientY-r.top)/r.height*100,
      text:"",done:false,isNew:true});
    render(); open(pins.length-1);
  });
});
$("#ok").onclick=()=>{ const p=pins[cur]; p.text=$("#txt").value.trim(); delete p.isNew;
  if(!p.text) pins.splice(cur,1); sheet.classList.remove("on"); save(); };
$("#cancel").onclick=()=>{ if(pins[cur]&&pins[cur].isNew) pins.splice(cur,1);
  sheet.classList.remove("on"); render(); };
$("#del").onclick=()=>{ pins.splice(cur,1); sheet.classList.remove("on"); save(); };
load();

/* ===== 原型交互层（只作用于画板内部，不影响批注） ===== */
const REDUCE = matchMedia("(prefers-reduced-motion: reduce)").matches;
const toast=(m)=>{const t=$("#toast");t.textContent=m;t.className="on";clearTimeout(t._x);t._x=setTimeout(()=>t.className="",1400)};

/* 1) 悬浮排列：真的能滑，带视差 —— 焦点 1.0x / 邻居 0.86x / 背景 0.3x */
(function(){
  const car=document.querySelector("[data-carousel]"); if(!car) return;
  const C=car.querySelector("[data-card=center]"), L=car.querySelector("[data-card=left]"), R=car.querySelector("[data-card=right]");
  const names=["cc-mail","cc-Yxi","cc-nanobanana","cc-项目","cc-root"]; let idx=1, x0=null, dx=0;
  const apply=(d,anim)=>{
    const k=d/300;                                  // -1..1
    const t=anim&&!REDUCE?"transform .34s cubic-bezier(.2,.8,.2,1),opacity .34s":"none";
    [C,L,R].forEach(e=>e.style.transition=t);
    C.style.transform=`translateX(${d}px) scale(${1-Math.abs(k)*.08})`;
    C.style.opacity=String(1-Math.abs(k)*.25);
    L.style.transform=`translateX(${d*.86}px) scale(${.86+Math.max(0,k)*.1})`;
    L.style.opacity=String(.55+Math.max(0,k)*.4);
    R.style.transform=`translateX(${d*.86}px) scale(${.86+Math.max(0,-k)*.1})`;
    R.style.opacity=String(.55+Math.max(0,-k)*.4);
    car.style.transition=t; car.style.backgroundPosition=`${d*.3}px 0`;   // 背景 0.3x
  };
  const end=()=>{ if(x0===null) return;
    if(Math.abs(dx)>70){ idx=(idx+(dx<0?1:-1)+names.length)%names.length;
      const t=C.querySelector("div[style*='font-weight:600']"); if(t) t.textContent=names[idx];
      toast("切到 "+names[idx]); }
    x0=null; dx=0; apply(0,true); };
  const start=e=>{x0=(e.touches?e.touches[0]:e).clientX; dx=0;};
  const move=e=>{ if(x0===null) return; dx=(e.touches?e.touches[0]:e).clientX-x0; apply(dx,false); if(e.cancelable)e.preventDefault(); };
  car.addEventListener("touchstart",start,{passive:true});
  car.addEventListener("touchmove",move,{passive:false});
  car.addEventListener("touchend",end);
  car.addEventListener("mousedown",e=>{start(e);const mm=ev=>move(ev),mu=()=>{end();removeEventListener("mousemove",mm);removeEventListener("mouseup",mu)};addEventListener("mousemove",mm);addEventListener("mouseup",mu)});
})();

/* 2) 顶部 终端/对话/文件 —— 点了真跳到对应画板 */
(function(){
  const to={"终端":"终端模式","对话":"对话模式 · 主界面","文件":"文件模式"};
  document.querySelectorAll(".stage div").forEach(el=>{
    const t=el.textContent.trim();
    if(!to[t]||el.children.length||!/border-radius:100px/.test(el.getAttribute("style")||"")) return;
    el.style.cursor="pointer";
    el.addEventListener("click",e=>{ e.stopPropagation();
      const st=[...document.querySelectorAll(".stage")].find(s=>s.dataset.title===to[t]);
      if(st){ st.scrollIntoView({behavior:REDUCE?"auto":"smooth",block:"center"}); toast("→ "+to[t]); }
    },true);
  });
})();

/* 3) 思考折叠条 —— 点了展开 */
document.querySelectorAll("[data-collapse=thinking]").forEach(el=>{
  el.style.cursor="pointer"; let open=false;
  el.addEventListener("click",e=>{ e.stopPropagation(); open=!open;
    let body=el.nextElementSibling;
    if(!body||!body.dataset.thinkingBody){
      body=document.createElement("div"); body.dataset.thinkingBody="1";
      body.style.cssText="font-size:13.5px;line-height:1.7;color:#8a7d72;padding:0 4px;overflow:hidden;height:0;transition:height .3s ease";
      body.textContent="先确认目标架构，再看脚本里有没有写死的下载地址——多半是 ARCH 变量被固定成了 x86_64。";
      el.after(body);
    }
    body.style.height=open?body.scrollHeight+"px":"0";
    el.style.background=open?"#2d2925":"#1e1b17";
  },true);
});

/* 4) 入场：画板滚进视口时抬起；进度条滚进来才填充 */
if(!REDUCE){
  const io=new IntersectionObserver(es=>es.forEach(e=>{
    if(!e.isIntersecting) return;
    e.target.style.transition="opacity .5s ease, transform .5s cubic-bezier(.2,.8,.2,1)";
    e.target.style.opacity="1"; e.target.style.transform="none";
    e.target.querySelectorAll("div[style*='height:7px'] > div, div[style*='height:5px'] > div, div[style*='height:6px'] > div, div[style*='height:4px'] > div").forEach(b=>{
      const w=b.style.width; b.style.width="0"; b.style.transition="width .8s cubic-bezier(.2,.8,.2,1) .15s";
      requestAnimationFrame(()=>requestAnimationFrame(()=>b.style.width=w));
    });
    io.unobserve(e.target);
  }),{threshold:.15});
  document.querySelectorAll(".stage").forEach(s=>{ s.style.opacity="0"; s.style.transform="translateY(18px)"; io.observe(s); });
}
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def _auth(self):
        # 路径必须以 /<token>/ 开头；其余一律 404（不泄露 token 是否存在）
        m = re.match(r"^/([A-Za-z0-9_-]{8,64})(/.*)?$", self.path)
        return m.group(2) or "/" if m and m.group(1) == TOKEN else None

    def do_GET(self):
        rest = self._auth()
        if rest is None:
            return self._send(404, "not found", "text/plain")
        if rest == "/":
            return self._send(200, page())
        if rest == "/pins":
            return self._send(200, json.dumps(load_pins(), ensure_ascii=False), "application/json")
        if rest == "/apk":
            # 手机直接下 APK —— 走的是同一个 token 路径，没 token 就 404。
            # 加这条是因为到笔电的反向隧道会断，而手机装包不该被那条链路卡住。
            # 文件名固定，内容永远是最新构建 —— 版本号写在文件名里的话，
            # 每发一版都要改这里和用户手上的链接，那个链接迟早会失效
            apk = HERE.parent.parent / "Yxi-0.1.0-debug.apk"
            if not apk.exists():
                return self._send(404, "no apk", "text/plain")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(apk.stat().st_size))
            self.send_header("Content-Disposition", 'attachment; filename="Yxi.apk"')
            self.end_headers()
            with apk.open("rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return
        self._send(404, "not found", "text/plain")

    def do_POST(self):
        rest = self._auth()
        if rest != "/pins":
            return self._send(404, "not found", "text/plain")
        n = int(self.headers.get("Content-Length", 0))
        if n > 512 * 1024:
            return self._send(413, "too large", "text/plain")
        try:
            data = json.loads(self.rfile.read(n))
            assert isinstance(data, list) and len(data) <= 500
        except Exception:
            return self._send(400, "bad json", "text/plain")
        with LOCK:
            PINS.write_text(json.dumps(data, ensure_ascii=False, indent=1))
        self._send(200, '{"ok":true}', "application/json")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
