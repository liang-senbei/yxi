"""把 16 张谱的 lane 从 4 轨铺到 12 轨（lanes.spread）。只改 lane，不改 units。用法：python3 relane_all.py"""
import json, os, glob
from lanes import spread
d = os.path.join(os.path.dirname(__file__), '../../android/app/src/main/assets/charts')
for p in sorted(glob.glob(os.path.join(d, 'chart_*.json'))):
    c = json.load(open(p))
    if max(n['lane'] for n in c['notes']) >= 4:
        print(os.path.basename(p), '已经是 12 轨，跳过'); continue
    c['notes'] = spread(c['notes'], c['song'] + c['difficulty'])
    json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
    used = sorted({n['lane'] for n in c['notes']})
    print(f"{os.path.basename(p):26} n={len(c['notes']):3} 用到的轨={used}")
