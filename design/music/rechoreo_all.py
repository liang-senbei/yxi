"""把 16 张谱的 `lines`（判定线编舞）按 choreo.py 重写。只动 lines，不动 notes（服务端按 units 校验，不受影响）。
用法：python3 rechoreo_all.py [charts 目录]"""
import json, os, sys, glob
from choreo import choreo

d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '../../android/app/src/main/assets/charts')
for p in sorted(glob.glob(os.path.join(d, 'chart_*.json'))):
    c = json.load(open(p))
    spb = 60.0 / c['bpm']
    notes = c['notes']
    first = min(n['t'] for n in notes)
    phase = first - int(first / (4 * spb)) * 4 * spb            # 第一个音符所在小节的小节线（近似强拍）
    dur = max(n['t'] + n.get('dur', 0) for n in notes) + 2.0
    c['lines'] = choreo(c['song'], spb, phase, dur, c['difficulty'] == 'hard')
    json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
    degs = sorted({e['to'] for e in c['lines'] if e['op'] == 'rotate'})
    print(f"{os.path.basename(p):26} lines={len(c['lines']):3} 角度={degs}")
