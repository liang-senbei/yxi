"""给现成的 hard 谱加多根判定线的舞（老板 2026-09-07 晚：「多根校准线舞动的效果为什么还没实装」——之前只有两张狂热谱有）。

规则：
- 按能量挑 2~3 个 8 小节段（最响的优先，段之间至少隔 4 小节）；
- 每段随机一种玩法：`split`（主线原地分裂出一根，错开 ±22° 上挪，结束前合回）/ `side`（斜 ±32° 的副线从边上进来）/
  `pair`（分裂出两根：一根上挪一根下挪，三根一起舞）；
- 段内音符：split / side 交替接（主 / 副 / 主 / 副…），pair 三线轮流；和弦的伴音去副线；
- 副线的关键帧用 frenzy_chart 的 split_choreo / side_choreo；judges 追加，notes 的 line 指向对应下标；
- units 不变（只改 line），服务端不用动。
用法：python3 multiline.py <chart_*_hard.json> …（原地改写；已经有 judges 的跳过，除非 --force）
"""
import json, os, random, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from frenzy_chart import split_choreo, side_choreo


def add_side_lines(c, force=False):
    if c.get('judges') and not force:
        return False
    rnd = random.Random(f"multi-{c['song']}-{c['difficulty']}")
    bpm = c['bpm']; spb = 60.0 / bpm; bar = 4 * spb
    notes = c['notes']
    t0 = min(n['t'] for n in notes); t1 = max(n['t'] + n.get('dur', 0) for n in notes)
    phase = t0 - int(t0 / bar) * bar
    energy = (c.get('energy') or {}).get('v') or []
    hz = (c.get('energy') or {}).get('hz', 4)
    def e_of(a, b):
        if not energy: return 0.5
        i0, i1 = int(a * hz), int(b * hz)
        return sum(energy[i0:max(i0 + 1, i1)]) / max(1, i1 - i0)
    n_bars = int((t1 - phase) / bar)
    segs = [(b, b + 8) for b in range(4, n_bars - 8, 4)]
    if not segs:
        return False
    segs.sort(key=lambda s: -e_of(phase + s[0] * bar, phase + s[1] * bar))
    chosen = []
    for s in segs:
        if all(s[1] + 4 <= a or s[0] >= b + 4 for a, b in chosen):
            chosen.append(s)
        if len(chosen) >= rnd.choice([2, 3]): break
    chosen.sort()
    main_lines = c.get('lines', [])
    judges = [dict(lines=main_lines)]
    for n in notes: n['line'] = 0
    for (a, b) in chosen:
        f, to = phase + a * bar, phase + b * bar
        kind = rnd.choice(['split', 'side', 'pair', 'split'])
        idxs = []
        if kind == 'side':
            judges.append(dict(**{'from': round(f - 0.4, 3), 'to': round(to + 0.4, 3)}, lines=side_choreo(2, [(f, to)], spb, rnd))); idxs = [len(judges) - 1]
        elif kind == 'split':
            judges.append(dict(**{'from': round(f - 0.4, 3), 'to': round(to + 0.4, 3)}, lines=split_choreo(main_lines, [(f - 0.4, to + 0.4)], spb, rnd))); idxs = [len(judges) - 1]
        else:                                                   # pair：上下各一根
            for sign in (1, -1):
                ks = split_choreo(main_lines, [(f - 0.4, to + 0.4)], spb, rnd)
                for e in ks:
                    if e['op'] == 'move_y' and sign < 0:          # 下面那根：上挪改成下挪
                        for key in ('from', 'to'):
                            e[key] = round(-(e[key]) if abs(e[key]) > 0.2 else e[key], 4)
                judges.append(dict(**{'from': round(f - 0.4, 3), 'to': round(to + 0.4, 3)}, lines=ks)); idxs.append(len(judges) - 1)
        # 段内音符轮流分给 主 + 副线们
        cycle = [0] + idxs
        by_t = {}
        for n in notes:
            if f <= n['t'] <= to:
                by_t.setdefault(round(n['t'], 3), []).append(n)
        for k, key in enumerate(sorted(by_t)):
            grp = by_t[key]
            if len(grp) >= 2:                                   # 和弦：伴音去副线
                grp.sort(key=lambda n: abs(n['lane'] - 5.5))
                grp[-1]['line'] = idxs[k % len(idxs)]
            else:
                grp[0]['line'] = cycle[k % len(cycle)]
    c['judges'] = judges
    return True


if __name__ == '__main__':
    force = '--force' in sys.argv
    for p in [a for a in sys.argv[1:] if not a.startswith('--')]:
        c = json.load(open(p))
        if add_side_lines(c, force):
            json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
            lines = len(c['judges']); moved = sum(1 for n in c['notes'] if n.get('line', 0) > 0)
            print(f"{os.path.basename(p):28} 判定线 {lines} 条，{moved} 个音符落到副线")
        else:
            print(f"{os.path.basename(p):28} 跳过（已有 judges）")
