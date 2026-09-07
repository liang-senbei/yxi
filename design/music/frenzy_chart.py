"""「狂热」谱（老板 2026-09-07：「做一个非常非常难且非常有视觉观赏性的曲子，节拍也要有观赏性」）。

和 easy / hard 的区别：
- 网格 1/4 拍（16 分音符），起音阈值放到 30% 分位 → 密度是 hard 的 2~3 倍；
- 强拍上起音够强就出**和弦**（2~3 个音符同时，至少隔 3 条轨）；
- 每个乐句（4 小节）开头插一个**花样**（motif）：阶梯（12 条轨依次跑过一小节）、镜像（对称成对）、
  扇形（从中间往两边开）、之字（左右横跳）—— 这些是给眼睛看的；
- 轨道直接按频谱质心分到 12 条，一步最多跨 5 条；
- swipe 12% / trace 8% / slide 4%（长按会把窗口清空，太多就不密了）；
- 下落 1.15 秒（hard 1.45）；编舞用 choreo 的狂热档（4 小节一段，倾 ±20°、每段都动，副歌整段旋转）。

服务端要知道新谱的 units（charts-meta.json），演示不上报，但真打要。
用法：python3 frenzy_chart.py <song_id> <zh> <audio> [--bpm N]
"""
import argparse, json, os, random, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chart_from_audio import analyze
from choreo import choreo, SCENES, _Track
from energy_all import envelope

LANES = 12


def motif(kind, t0, step, rnd):
    """返回 [(t, lane)]。step = 一个 16 分的时长。"""
    if kind == 'stair':                                   # 12 条轨依次跑过去（一小节 16 个格里放 12 个）
        up = rnd.random() < 0.5
        return [(t0 + i * step, (i if up else 11 - i)) for i in range(12)]
    if kind == 'mirror':                                  # 对称成对，从两边往中间合
        return [(t0 + i * step * 2, l) for i in range(4) for l in (i, 11 - i)]
    if kind == 'fan':                                     # 从中间往两边开
        return [(t0 + i * step * 2, l) for i in range(4) for l in (5 - i, 6 + i)]
    if kind == 'zigzag':                                  # 左右横跳
        seq = [1, 10, 2, 9, 3, 8, 4, 7]
        return [(t0 + i * step, l) for i, l in enumerate(seq)]
    return []


def build_frenzy(a, song_id, zh):
    rnd = random.Random(f'frenzy-{song_id}')
    spb, phase, dur = a['spb'], a['phase'], a['dur']
    step = spb / 4
    grid = []
    t = phase + 2 * spb
    while t < dur - 1.5:
        grid.append(t); t += step
    strengths = np.array([a['onset'](t) for t in grid])
    thr = float(np.quantile(strengths, 0.30))
    cs = np.array([a['cent'](t) for t in grid])
    q = np.quantile(cs, np.linspace(0, 1, LANES + 1)[1:-1])

    notes = []
    occupied = {}                                          # 取整到 1ms 的时刻 → 已用轨
    def put(t, lane, kind='tick', dur_=0.0, dir_=0):
        key = round(t, 3)
        used = occupied.setdefault(key, [])
        if any(abs(lane - u) < 3 for u in used):          # 同时的音符至少隔 3 条
            return False
        if lane < 0 or lane >= LANES:
            return False
        notes.append(dict(t=round(t, 4), lane=lane, type=kind, dur=dur_, dir=dir_))
        used.append(lane)
        return True

    # 1) 花样：每 4 小节的段首一个，类型轮着来
    bar = 4 * spb
    kinds = ['stair', 'mirror', 'fan', 'zigzag']
    motif_times = set()
    seg_i = 0
    tt = phase + 4 * bar
    while tt < dur - 2 * bar:
        for (mt, ml) in motif(kinds[seg_i % 4], tt, step, rnd):
            if put(mt, ml):
                motif_times.add(round(mt, 3))
        seg_i += 1
        tt += 4 * bar

    # 2) 网格音符
    prev_lane = 5
    hold_until = -1.0
    quota = dict(swipe=int(len(grid) * 0.12 * 0.5), trace=int(len(grid) * 0.08 * 0.5), slide=int(len(grid) * 0.04 * 0.5))
    used = dict(swipe=0, trace=0, slide=0)
    last_kind = 'tick'
    for i, t in enumerate(grid):
        key = round(t, 3)
        if key in motif_times or t < hold_until:
            continue
        s = strengths[i]
        if s < thr:
            continue
        lane = int(np.searchsorted(q, cs[i]))
        if abs(lane - prev_lane) > 5:
            lane = prev_lane + (5 if lane > prev_lane else -5)
        lane = max(0, min(LANES - 1, lane))
        on_beat = abs(((t - phase) / spb) - round((t - phase) / spb)) < 1e-3
        strong = s > np.quantile(strengths, 0.80)
        kind, d, dr = 'tick', 0.0, 0
        nxt_strong = strengths[i + 1] > thr if i + 1 < len(grid) else False
        sustained = all(a['rms'](t + k * step) >= 0.7 * a['rms'](t) for k in range(1, 6)) and not nxt_strong
        if sustained and used['slide'] < quota['slide'] and last_kind != 'slide':
            kind, d = 'slide', round(1.5 * spb, 3); used['slide'] += 1
            hold_until = t + d + 0.15
        elif on_beat and used['swipe'] < quota['swipe'] and last_kind != 'swipe' and rnd.random() < 0.35:
            kind = 'swipe'; dr = 1 if (i + 1 < len(grid) and cs[i + 1] > cs[i]) else -1; used['swipe'] += 1
        elif abs(lane - prev_lane) >= 3 and used['trace'] < quota['trace'] and last_kind != 'trace' and rnd.random() < 0.5:
            kind = 'trace'; dr = 1 if lane > prev_lane else -1; used['trace'] += 1
        if put(t, lane, kind, d, dr):
            last_kind = kind
            prev_lane = lane
            # 和弦：强拍 + 起音很强 → 再加 1~2 个，隔 3 条以上
            if on_beat and strong and kind == 'tick':
                for extra in rnd.sample([lane - 4, lane + 4, lane - 7, lane + 7], 2 if rnd.random() < 0.4 else 1):
                    put(t, extra)
    notes.sort(key=lambda n: (n['t'], n['lane']))
    # slide 期间清场（花样可能落在里面）
    holds = [(n['t'], n['t'] + n['dur'] + 0.15) for n in notes if n['type'] == 'slide']
    notes = [n for n in notes if n['type'] == 'slide' or not any(h0 < n['t'] < h1 for h0, h1 in holds)]
    for n in notes:
        n['line'] = 0
    return notes


def assign_lines(notes, energy, spb, phase, dur, rnd):
    """多判定线（老板 09-07 发的 Phigros 视频：线可以多根、随机出现、每根上都能校准）。
    第 1 条：在能量最高的两段（各 8 小节）出现，接走和弦的伴音和一半的花样；
    第 2 条：斜着的，在另两段（各 4 小节）出现，接走阶梯 / 之字花样。
    返回 judges 的 [from, to] 窗口列表：[(line, from, to)]。"""
    bar = 4 * spb
    n_bars = int((dur - phase) / bar)
    def e_at(t):
        i = int(t * 4); return energy[min(len(energy) - 1, max(0, i))]
    seg8 = [(phase + b * bar, phase + (b + 8) * bar) for b in range(4, n_bars - 8, 8)]
    seg8.sort(key=lambda w: -sum(e_at(w[0] + k * 0.5) for k in range(int((w[1] - w[0]) / 0.5))))
    win1 = sorted(seg8[:2])
    used = set(win1)
    seg4 = [(phase + b * bar, phase + (b + 4) * bar) for b in range(6, n_bars - 4, 4)]
    seg4 = [w for w in seg4 if not any(a[0] - 0.1 <= w[0] < a[1] or a[0] < w[1] <= a[1] + 0.1 for a in win1)]
    rnd.shuffle(seg4)
    win2 = sorted(seg4[:2])
    # 分配：窗口里的音符，按时刻分组
    by_t = {}
    for n in notes:
        by_t.setdefault(round(n['t'], 3), []).append(n)
    for key, grp in by_t.items():
        t = grp[0]['t']
        in1 = any(a <= t <= b for a, b in win1)
        in2 = any(a <= t <= b for a, b in win2)
        if in1 and len(grp) >= 2:                          # 和弦：伴音去第 1 条线（最靠边的那个）
            grp.sort(key=lambda n: abs(n['lane'] - 5.5))
            grp[-1]['line'] = 1
        elif in1 and rnd.random() < 0.30:
            grp[0]['line'] = 1
        elif in2 and rnd.random() < 0.55:
            grp[0]['line'] = 2
    return [(1, a, b) for a, b in win1] + [(2, a, b) for a, b in win2]


def choreo_frenzy(song_id, spb, phase, dur):
    """狂热编舞：4 小节一段、每段都换场景、幅度大；中段整段 360° 慢转（linear）。"""
    rnd = random.Random(f'frenzy-choreo-{song_id}')
    bars = int((dur - phase) / (4 * spb))
    rot, mx, my = _Track('rotate'), _Track('move_x'), _Track('move_y')
    trans = min(1.2, max(0.5, 1.5 * spb))
    seg = 4
    n_seg = max(1, (bars - 2) // seg)
    pool = ['tiltL', 'tiltR', 'rise', 'slideL', 'slideR', 'vertical', 'vertNeg', 'flip']
    seq, last = ['flat'], 'flat'
    for _ in range(1, n_seg):
        c = rnd.choice([p for p in pool if p != last]); seq.append(c); last = c
    spin_at = n_seg // 2                                    # 中段：转一整圈
    for i, name in enumerate(seq):
        t = phase + (2 + i * seg) * 4 * spb
        if i == spin_at:
            base = rot.cur
            spin = round(seg * 4 * spb, 3)
            rot.out.append(dict(t=round(t, 3), dur=spin, op='rotate', **{'from': round(base, 4), 'to': round(base + 360.0, 4)}, ease='linear'))
            # 转完瞬间把角度记回 base（同一个方向，dur=0 立即生效），不然下一段从 540° 往回倒着甩一整圈
            rot.out.append(dict(t=round(t + spin, 3), dur=0.0, op='rotate', **{'from': round(base + 360.0, 4), 'to': round(base, 4)}, ease='linear'))
            rot.cur = base
            my.to(t, trans, -0.30)
            continue
        sc = SCENES[name]
        k = 1.5 if name in ('tiltL', 'tiltR', 'slideL', 'slideR') else 1.0
        rot.to(t, trans, sc['deg'] * k)
        mx.to(t, trans, sc['dx'] * k)
        my.to(t, trans, sc['dy'])
    dips = []
    for b in range(2, bars - 1, 1):                         # 狂热：每小节都沉一下
        t = phase + b * 4 * spb
        busy = any(e['t'] - 0.05 <= t <= e['t'] + e['dur'] + 0.45 for e in my.out)
        if busy:
            continue
        base = 0.0
        for e in my.out:
            if e['t'] <= t: base = e['to']
        dips.append(dict(t=round(t, 3), dur=0.06, op='move_y', **{'from': round(base, 4), 'to': round(base + 0.03, 4)}, ease='easeOut'))
        dips.append(dict(t=round(t + 0.06, 3), dur=0.30, op='move_y', **{'from': round(base + 0.03, 4), 'to': round(base, 4)}, ease='cubicInOut'))
    return sorted(rot.out + mx.out + my.out + dips, key=lambda e: (e['t'], e['op']))


def side_choreo(line, windows, spb, rnd):
    """副线的动作：第 1 条平行在主线上方（dy -0.42）轻轻摆；第 2 条斜着（±32°）从边上进来。每个窗口自己一套关键帧。"""
    rot, mx, my = _Track('rotate'), _Track('move_x'), _Track('move_y')
    out = []
    for (a, b) in windows:
        trans = min(1.0, max(0.5, 1.5 * spb))
        if line == 1:
            out += [dict(t=round(a, 3), dur=0.0, op='move_y', **{'from': -0.42, 'to': -0.42}, ease='linear'),
                    dict(t=round(a, 3), dur=round(b - a, 3), op='rotate', **{'from': -6.0, 'to': 6.0}, ease='cubicInOut')]
        else:
            deg = rnd.choice([32.0, -32.0])
            out += [dict(t=round(a, 3), dur=0.0, op='move_y', **{'from': -0.25, 'to': -0.25}, ease='linear'),
                    dict(t=round(a, 3), dur=0.0, op='move_x', **{'from': -deg / 160.0, 'to': -deg / 160.0}, ease='linear'),
                    dict(t=round(a, 3), dur=trans, op='rotate', **{'from': deg * 1.4, 'to': deg}, ease='cubicInOut')]
    return sorted(out, key=lambda e: (e['t'], e['op']))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('song_id'); ap.add_argument('zh'); ap.add_argument('audio')
    ap.add_argument('--bpm', type=float, default=None); ap.add_argument('--out', default='.')
    args = ap.parse_args()
    a = analyze(args.audio, args.bpm)
    notes = build_frenzy(a, args.song_id, args.zh)
    energy = envelope(args.audio)
    rnd = random.Random(f'lines-{args.song_id}')
    wins = assign_lines(notes, energy, a['spb'], a['phase'], a['dur'], rnd)
    main_lines = choreo_frenzy(args.song_id, a['spb'], a['phase'], a['dur'])
    judges = [dict(lines=main_lines)]
    for line in (1, 2):
        ws = [(f, to) for (l, f, to) in wins if l == line]
        for (f, to) in ws:                                   # 每个窗口一条 judge（出现 / 消失各自淡入淡出）
            judges.append(dict(**{'from': round(f - 0.4, 3), 'to': round(to + 0.4, 3)}, lines=side_choreo(line, [(f, to)], a['spb'], rnd)))
    # 音符的 line 号要对上 judges 的下标：窗口按 (line, from) 顺序追加
    idx = {}
    for j_i, j in enumerate(judges[1:], start=1):
        idx[(j['from'], j['to'])] = j_i
    for n in notes:
        if n.get('line', 0) > 0:
            for (l, f, to) in wins:
                if l == n['line'] and f <= n['t'] <= to:
                    n['line'] = idx[(round(f - 0.4, 3), round(to + 0.4, 3))]; break
            else:
                n['line'] = 0
    c = dict(song=args.song_id, zh=args.zh, bpm=int(round(a['tempo'])), difficulty='frenzy', offset=0, approach=1.15,
             lines=main_lines, judges=judges, energy={'hz': 4, 'v': energy}, notes=notes)
    os.makedirs(args.out, exist_ok=True)
    p = os.path.join(args.out, f'chart_{args.song_id}_frenzy.json')
    json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
    cnt = {k: sum(1 for x in notes if x['type'] == k) for k in ('tick', 'slide', 'trace', 'swipe')}
    units = len(notes) + cnt['slide']
    meta = dict(id=f'{args.song_id}_frenzy', song=args.song_id, zh=args.zh, difficulty='frenzy', notes=len(notes),
                ticks=cnt['tick'], slides=cnt['slide'], traces=cnt['trace'], swipes=cnt['swipe'], units=units, durationMs=int(a['dur'] * 1000))
    json.dump(meta, open(os.path.join(args.out, f'meta_{args.song_id}_frenzy.json'), 'w'), ensure_ascii=False)
    print(f'{args.zh} frenzy bpm={c["bpm"]} 音符 {len(notes)}（{cnt}） units={units} 编舞 {len(c["lines"])} 帧 → {p}')
    print('meta:', json.dumps(meta, ensure_ascii=False))


if __name__ == '__main__':
    main()
