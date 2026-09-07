"""把四个家族的 manifest_*.json + wav 拼成一张试听页（wav 以 data URI 内联，单文件，直接丢到 /lab/ 下）。
用法：python3 assemble_page.py [输出路径，默认 ../../shatter-sfx.html]"""
import base64, glob, json, os, sys, wave

here = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, '../../shatter-sfx.html')
FAMILY = {'glass': '玻璃 / 水晶', 'ice': '冰 / 陶瓷 / 石', 'digital': '电子 / 数字', 'magic': '光 / 魔法 / 柔和'}

items = []
for fam in FAMILY:
    mp = os.path.join(here, f'manifest_{fam}.json')
    if not os.path.exists(mp):
        continue
    for e in json.load(open(mp)):
        p = os.path.join(here, e['file'])
        with wave.open(p) as w:
            secs = w.getnframes() / w.getframerate()
        b64 = base64.b64encode(open(p, 'rb').read()).decode()
        items.append(dict(fam=fam, name=e['name'], desc=e.get('desc', ''), file=e['file'], secs=secs, b64=b64))

cards = []
for i, it in enumerate(items, 1):
    cards.append(f'''<div class="card" data-fam="{it['fam']}"><button class="play" data-i="{i}" aria-label="播放 {it['name']}">▶</button>
<div class="meta"><div class="name">{i:02d} · {it['name']}</div><div class="desc">{it['desc']}</div><div class="sub">{FAMILY[it['fam']]} · {it['secs']:.2f}s · {it['file']}</div></div>
<audio id="a{i}" preload="auto" src="data:audio/wav;base64,{it['b64']}"></audio></div>''')

html = f'''<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>碎裂音效 · 试听</title>
<style>
:root{{--bg:#0b0d12;--card:#151924;--ink:#e8edf5;--mute:#8a93a6;--acc:#f97316}}
body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,"PingFang SC","Noto Sans CJK SC",system-ui,sans-serif}}
header{{padding:22px 20px 8px}}h1{{margin:0;font-size:20px;font-weight:600}}p{{margin:6px 0 0;color:var(--mute);font-size:13px}}
.tabs{{display:flex;gap:8px;padding:10px 20px;flex-wrap:wrap}}.tabs button{{background:var(--card);color:var(--ink);border:1px solid #232a3a;border-radius:999px;padding:6px 14px;font-size:13px}}
.tabs button.on{{border-color:var(--acc);color:var(--acc)}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:10px;padding:10px 20px 40px}}
.card{{display:flex;gap:12px;align-items:center;background:var(--card);border-radius:14px;padding:12px}}
.card.hide{{display:none}}
.play{{width:48px;height:48px;border-radius:50%;border:0;background:var(--acc);color:#fff;font-size:18px;flex:none}}
.play.on{{background:#fff;color:var(--acc)}}
.name{{font-weight:600}}.desc{{color:var(--ink);opacity:.85;font-size:13px}}.sub{{color:var(--mute);font-size:12px;margin-top:2px}}
.bar{{position:sticky;bottom:0;background:#0b0d12e6;backdrop-filter:blur(8px);padding:10px 20px;display:flex;gap:10px;align-items:center;border-top:1px solid #1c2230}}
.bar button{{background:var(--card);color:var(--ink);border:1px solid #232a3a;border-radius:10px;padding:8px 14px}}
.bar input{{flex:1;background:var(--card);color:var(--ink);border:1px solid #232a3a;border-radius:10px;padding:8px 10px}}
</style></head><body>
<header><h1>碎裂音效 · 20 款试听</h1><p>点 ▶ 播放；可以连点几个对比。挑好把编号或名字发给 cc-Yxi 即可（例：「03 薄玻璃、12 像素崩」）。</p></header>
<div class="tabs"><button class="on" data-f="">全部</button>''' + ''.join(f'<button data-f="{k}">{v}</button>' for k, v in FAMILY.items()) + f'''</div>
<div class="grid">{''.join(cards)}</div>
<div class="bar"><button id="seq">顺序连播</button><input id="pick" placeholder="记一下喜欢的编号…（只存在本机浏览器）"></div>
<script>
const P=[...document.querySelectorAll('.play')];
function play(i){{const a=document.getElementById('a'+i);a.currentTime=0;a.play();const b=P[i-1];b.classList.add('on');setTimeout(()=>b.classList.remove('on'),Math.max(300,a.duration*1000||400));}}
P.forEach(b=>b.onclick=()=>play(+b.dataset.i));
document.querySelectorAll('.tabs button').forEach(t=>t.onclick=()=>{{document.querySelectorAll('.tabs button').forEach(x=>x.classList.remove('on'));t.classList.add('on');document.querySelectorAll('.card').forEach(c=>c.classList.toggle('hide',!!t.dataset.f&&c.dataset.fam!==t.dataset.f));}});
document.getElementById('seq').onclick=async()=>{{for(const b of P){{if(b.closest('.card').classList.contains('hide'))continue;play(+b.dataset.i);await new Promise(r=>setTimeout(r,900));}}}};
const pk=document.getElementById('pick');try{{pk.value=localStorage.getItem('shatter-pick')||''}}catch(e){{}}pk.oninput=()=>{{try{{localStorage.setItem('shatter-pick',pk.value)}}catch(e){{}}}};
</script></body></html>'''
open(out, 'w').write(html)
print(f'{len(items)} 款 → {out} ({os.path.getsize(out)//1024} KB)')
