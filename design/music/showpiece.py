"""表演关（老板 2026-09-07：「五六根校准线，变成菱形、正方形，闪闪闪、放大缩小，视觉表现力更好」）。

用 levelkit 手写编排 + 音频分析定节奏：
- 主线跟 frenzy 一样按小节铺节奏模板（借 frenzy_chart 的 TEMPLATES / shape_lanes）；
- 曲子能量最高的两段（各 8 小节）进**阵型**：第一段菱形（4 条线）放大缩小 + 每两拍闪一下，第二段六边形（6 条线）整体旋转；
  阵型期间主线淡到 0.15、不接音符，音符按拍轮流落到各条边上（从外向内飞），强拍上对边同时来一对；
- 阵型之间的一段做**分裂**（主线分出一根，两根交替接音符）；
- 结尾正方形阵型收束（4 条线一起缩到中心再淡掉）。
用法：python3 showpiece.py <song_id> <曲名> <音频> [--bpm N] [--out 目录]   → chart_<id>_frenzy.json
"""
import argparse, json, os, random, sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chart_from_audio import analyze
from energy_all import envelope
from frenzy_chart import TEMPLATES, shape_lanes
from levelkit import Level


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('song_id'); ap.add_argument('zh'); ap.add_argument('audio')
    ap.add_argument('--bpm', type=float, default=None); ap.add_argument('--out', default='.')
    args = ap.parse_args()
    a = analyze(args.audio, args.bpm)
    spb, phase, dur = a['spb'], a['phase'], a['dur']
    rnd = random.Random(f'show-{args.song_id}')
    energy = envelope(args.audio)
    L = Level(args.song_id, args.zh, bpm=int(round(a['tempo'])), seconds=int(dur), difficulty='frenzy', approach=1.2)
    L.energy = {'hz': 4, 'v': energy}
    main = L.line()
    bar = 4 * spb
    n_bars = int((dur - 1.5 - phase) / bar)
    B = lambda b, beat=0, six=0: phase + b * bar + beat * spb + six * spb / 4

    # ── 找能量最高的两个 8 小节段做阵型，中间一段做分裂 ──
    def e_of(b0, b1):
        i0, i1 = int(B(b0) * 4), int(B(b1) * 4)
        return float(np.mean(energy[i0:i1])) if i1 > i0 else 0.0
    segs = [(b, b + 8) for b in range(6, n_bars - 8, 8)]
    segs.sort(key=lambda s: -e_of(*s))
    form1, form2 = sorted(segs[:2])
    split_seg = None
    for b in range(form1[1] + 1, form2[0] - 4):
        split_seg = (b, b + 4); break

    # ── 主线：逐小节节奏模板（阵型期间不铺）──
    all_s = np.array([a['onset'](phase + k * spb / 4) for k in range(n_bars * 16)])
    hi = float(np.quantile(all_s, 0.9)) or 1.0
    prev, same, prev_shape = None, 0, None
    in_form = lambda b: form1[0] <= b < form1[1] or form2[0] <= b < form2[1]
    for b in range(1, n_bars):
        if in_form(b):
            continue
        prof = np.clip(all_s[b * 16:(b + 1) * 16] / hi, 0, 1); e = float(np.mean(prof))
        strong = [k for k in range(16) if prof[k] > 0.55]; weak = [k for k in range(16) if prof[k] < 0.30]
        best, bs = 'eighth', -1e9
        for name, tpl in TEMPLATES.items():
            hits = [k for k in range(16) if tpl[k]]
            sc = sum(1 for k in strong if tpl[k]) / max(1, len(strong)) - 0.6 * sum(1 for k in hits if k in weak) / max(1, len(hits)) + 0.25 * len(hits) / 16 * e
            if name == 'run': sc -= 0.2 if (e > 0.7 and b % 4 == 3) else 1.0
            if name == 'burst': sc -= 0.0 if e > 0.7 else 0.5
            if name == 'eighth': sc += 0.12
            if name == prev: sc += 0.15 if same < 2 else -0.3
            sc += rnd.uniform(-0.04, 0.04)
            if sc > bs: best, bs = name, sc
        slots = [k for k in range(16) if TEMPLATES[best][k]]
        shape = rnd.choice([s for s in ('stairUp', 'stairDown', 'zigzag', 'hands', 'wave') if s != prev_shape])
        lanes = shape_lanes(shape, len(slots), rnd)
        target = main
        if split_seg and split_seg[0] <= b < split_seg[1]:
            pass                                            # 分裂段：下面单独铺
        else:
            for j, k in enumerate(slots):
                t = B(b, 0, k)
                if j == len(slots) - 1 and rnd.random() < 0.5: target.swipe(t, lanes[j], 1 if lanes[j] >= lanes[j - 1] else -1)
                elif j > 0 and abs(lanes[j] - lanes[j - 1]) >= 4 and rnd.random() < 0.4: target.trace(t, lanes[j], 1 if lanes[j] > lanes[j - 1] else -1)
                else: target.tap(t, lanes[j])
        same = same + 1 if best == prev else 0
        prev, prev_shape = best, shape
        # 每小节踩一下
        if not (split_seg and split_seg[0] - 1 <= b <= split_seg[1] + 1):
            main.dip(B(b))
    # 主线自己的动作：每 4 小节换个倾角
    for b in range(4, n_bars - 4, 4):
        if in_form(b) or (split_seg and split_seg[0] - 1 <= b <= split_seg[1] + 1): continue
        main.rotate(B(b), rnd.choice([-14, -7, 7, 14]), 0.9)

    # ── 分裂段：主线分一根出来，两根交替接八分音符 ──
    if split_seg:
        side = L.split(main, B(split_seg[0]), B(split_seg[1]))
        for b in range(*split_seg):
            for k in range(0, 16, 2):
                (main if (k // 2) % 2 == 0 else side).tap(B(b, 0, k), rnd.choice([2, 4, 6, 8, 10]))

    # ── 阵型 1：菱形，放大缩小 + 每两拍闪一下 ──
    def formation_notes(edges, b0, b1, dense):
        """按拍轮流落到各条边；强拍（每小节第 1 拍）对边同时来一对"""
        n = len(edges)
        for b in range(b0, b1):
            for beat in range(4):
                t = B(b, beat)
                if beat == 0:
                    i = (b * 4) % n
                    edges[i].tap(t, 5); edges[(i + n // 2) % n].tap(t, 6)
                else:
                    i = (b * 4 + beat) % n
                    edges[i].tap(t, rnd.choice([3, 5, 6, 8]))
                    if dense: edges[(i + 1) % n].tap(t + spb / 2, rnd.choice([4, 7]))
    t0, t1 = B(form1[0]) - 0.6, B(form1[1])
    main.alpha(t0 - 0.4, 0.15, 0.4)
    edges = L.formation('diamond', t0, t1, size=0.30, center=(0.0, -0.22), pulse=(4 * spb, 0.28), flash_every=2 * spb)
    formation_notes(edges, form1[0], form1[1], dense=False)
    main.alpha(t1, 1.0, 0.4)

    # ── 阵型 2：六边形，整体慢转 + 放大缩小 ──
    t0, t1 = B(form2[0]) - 0.6, B(form2[1])
    main.alpha(t0 - 0.4, 0.15, 0.4)
    edges = L.formation('hex', t0, t1, size=0.30, center=(0.0, -0.22), pulse=(8 * spb, 0.22), spin=360.0 / (t1 - t0))
    formation_notes(edges, form2[0], form2[1], dense=True)
    main.alpha(t1, 1.0, 0.4)

    # ── 结尾：正方形收束（4 条线缩到中心再淡掉）──
    last = min(n_bars - 1, form2[1] + 8)
    if last > form2[1] + 3:
        t0, t1 = B(last - 3), B(last) + 0.8
        edges = L.formation('square', t0, t1, size=0.34, center=(0.0, -0.22))
        for ln in edges:
            p = ln.pose_at(t0 + 0.5)
            ln.move_x(t1 - 1.2, p['move_x'] * 0.15, 1.0).move_y(t1 - 1.2, -0.22 + (p['move_y'] + 0.22) * 0.15, 1.0).alpha(t1 - 0.5, 0.0, 0.5)
        formation_notes(edges, last - 3, last - 1, dense=False)

    os.makedirs(args.out, exist_ok=True)
    p = os.path.join(args.out, f'chart_{args.song_id}_frenzy.json')
    L.save(p)
    print('阵型段：', form1, form2, '分裂段：', split_seg, '共', len(L.lines), '条线')


if __name__ == '__main__':
    main()
