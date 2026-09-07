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


# 一小节 16 格（4/4）的节奏模板（老板 09-07：「有些钢琴块太无规律了，要跟着节奏来」）：整小节用同一个模板，音符就有型了
TEMPLATES = {
    'quarter':  [1,0,0,0, 1,0,0,0, 1,0,0,0, 1,0,0,0],
    'eighth':   [1,0,1,0, 1,0,1,0, 1,0,1,0, 1,0,1,0],
    'gallop':   [1,0,0,1, 1,0,0,1, 1,0,0,1, 1,0,0,1],
    'offbeat':  [1,0,1,0, 0,0,1,0, 1,0,1,0, 0,0,1,0],
    'syncop':   [1,0,1,1, 0,1,0,1, 1,0,1,1, 0,1,0,1],
    'burst':    [1,1,1,1, 1,0,0,0, 1,1,1,1, 1,0,0,0],
    'run':      [1,1,1,1, 1,1,1,1, 1,1,1,1, 1,1,1,1],
}
# 一小节里音符落轨的「形状」：跑上 / 跑下 / 之字 / 左右手交替 / 波浪 —— 眼睛能读出规律
def shape_lanes(name, n, rnd):
    if n <= 0:
        return []
    if name == 'stairUp':
        s = rnd.randint(0, 3); return [min(11, s + i * max(1, 10 // max(1, n - 1))) for i in range(n)]
    if name == 'stairDown':
        s = rnd.randint(8, 11); return [max(0, s - i * max(1, 10 // max(1, n - 1))) for i in range(n)]
    if name == 'zigzag':
        lo, hi = rnd.randint(1, 3), rnd.randint(8, 10); return [lo + (i // 2) if i % 2 == 0 else hi - (i // 2) for i in range(n)]
    if name == 'hands':
        l, r = rnd.randint(2, 4), rnd.randint(7, 9); return [(l if i % 2 == 0 else r) + rnd.choice((-1, 0, 1)) for i in range(n)]
    c = rnd.randint(4, 7); amp = rnd.randint(3, 5)
    return [max(0, min(11, int(round(c + amp * np.sin(i * np.pi / max(2, n - 1) * 2))))) for i in range(n)]


def build_frenzy(a, song_id, zh):
    rnd = random.Random(f'frenzy-{song_id}')
    spb, phase, dur = a['spb'], a['phase'], a['dur']
    step = spb / 4
    bar = 4 * spb
    n_bars = int((dur - 1.5 - phase) / bar)
    notes = []
    occupied = {}
    def put(t, lane, kind='tick', dur_=0.0, dir_=0):
        key = round(t, 3); used = occupied.setdefault(key, [])
        if not 0 <= lane < LANES or any(abs(lane - u) < 3 for u in used):
            return False
        notes.append(dict(t=round(t, 4), lane=lane, type=kind, dur=dur_, dir=dir_)); used.append(lane); return True

    # 1) 花样：每 4 小节的段首（第 4 小节起）；这些小节不再铺模板
    kinds = ['stair', 'mirror', 'fan', 'zigzag']
    motif_bars = set()
    for si, b in enumerate(range(4, n_bars - 2, 4)):
        t0 = phase + b * bar
        for (mt, ml) in motif(kinds[si % 4], t0, step, rnd):
            put(mt, ml)
        motif_bars.add(b)

    # 2) 逐小节选模板 + 形状
    all_s = np.array([a['onset'](phase + k * step) for k in range(n_bars * 16)])
    hi = float(np.quantile(all_s, 0.9)) or 1.0
    quota = dict(swipe=int(n_bars * 0.6), trace=int(n_bars * 0.5), slide=int(n_bars * 0.12))
    used = dict(swipe=0, trace=0, slide=0)
    prev_tpl, prev_shape = None, None
    same_run = 0
    hold_until = -1.0
    for b in range(1, n_bars):
        if b in motif_bars:
            continue
        t0 = phase + b * bar
        prof = np.clip(all_s[b * 16:(b + 1) * 16] / hi, 0, 1)
        e = float(np.mean(prof))
        strong = [k for k in range(16) if prof[k] > 0.55]; weak = [k for k in range(16) if prof[k] < 0.30]
        best, best_score = 'eighth', -1e9
        for name, tpl in TEMPLATES.items():
            hits = [k for k in range(16) if tpl[k]]
            cover = sum(1 for k in strong if tpl[k]) / max(1, len(strong))          # 强起音有没有被接住
            junk = sum(1 for k in hits if k in weak) / max(1, len(hits))            # 打在没声音的格上
            dens = len(hits) / 16
            score = cover - 0.6 * junk + 0.25 * dens * e
            if name == 'run': score -= 0.2 if (e > 0.7 and b % 4 == 3) else 1.0     # 16 分连打只给响的小节末尾做填充
            if name == 'burst': score -= 0.0 if e > 0.7 else 0.5
            if name == 'eighth': score += 0.12                                      # 八分是底色
            if name == prev_tpl: score += 0.15 if same_run < 2 else -0.3            # 同型连两小节像乐句，连四小节就单调了
            score += rnd.uniform(-0.04, 0.04)
            if score > best_score: best, best_score = name, score
        tpl = TEMPLATES[best]
        slots = [k for k in range(16) if tpl[k]]
        shape = rnd.choice([s for s in ('stairUp', 'stairDown', 'zigzag', 'hands', 'wave') if s != prev_shape])
        lanes = shape_lanes(shape, len(slots), rnd)
        strong_bar = e > 0.7
        for j, k in enumerate(slots):
            t = t0 + k * step
            if t < hold_until:
                continue
            lane = lanes[j]
            kind, d, dr = 'tick', 0.0, 0
            # 长音：本小节是 quarter 且能量持得住 → 强拍变 slide（1.5 拍），后面清场
            if best == 'quarter' and k % 4 == 0 and used['slide'] < quota['slide'] and all(a['rms'](t + m * step) >= 0.7 * a['rms'](t) for m in range(1, 6)):
                kind, d = 'slide', round(1.5 * spb, 3); used['slide'] += 1; hold_until = t + d + 0.15
            elif j == len(slots) - 1 and used['swipe'] < quota['swipe'] and rnd.random() < 0.6:      # 小节末尾一划
                kind = 'swipe'; dr = 1 if lanes[j] >= lanes[max(0, j - 1)] else -1; used['swipe'] += 1
            elif j > 0 and abs(lane - lanes[j - 1]) >= 4 and used['trace'] < quota['trace'] and rnd.random() < 0.45:
                kind = 'trace'; dr = 1 if lane > lanes[j - 1] else -1; used['trace'] += 1
            if put(t, lane, kind, d, dr) and kind == 'tick' and k == 0 and strong_bar:
                put(t, lane + (4 if lane < 6 else -4))                # 强拍和弦
        same_run = same_run + 1 if best == prev_tpl else 0
        prev_tpl, prev_shape = best, shape
    notes.sort(key=lambda n: (n['t'], n['lane']))
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
    alt = [0]
    for n in notes:
        by_t.setdefault(round(n['t'], 3), []).append(n)
    for key, grp in by_t.items():
        t = grp[0]['t']
        in1 = any(a <= t <= b for a, b in win1)
        in2 = any(a <= t <= b for a, b in win2)
        if in1 and len(grp) >= 2:                          # 和弦：伴音去分裂出来的那条（最靠边的那个）
            grp.sort(key=lambda n: abs(n['lane'] - 5.5))
            grp[-1]['line'] = 1
        elif in1:                                          # 分裂期间两根线交替接音符（老板：两根都能校准）
            alt[0] ^= 1
            if alt[0]: grp[0]['line'] = 1
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


def pose_at(lines, t):
    """和 App 的 Chart.poseAt 一样：每个 op 取最近开始的那条关键帧在 t 的值。"""
    def ease(name, k):
        if name == 'linear': return k
        if name == 'cubicInOut': return 4 * k ** 3 if k < 0.5 else 1 - ((-2 * k + 2) ** 3) / 2
        return 1 - (1 - k) ** 3
    val = {'rotate': 0.0, 'move_x': 0.0, 'move_y': 0.0}
    for e in sorted(lines, key=lambda e: e['t']):
        if e['t'] > t: break
        if e['dur'] <= 0 or t >= e['t'] + e['dur']: val[e['op']] = e['to']
        else: val[e['op']] = e['from'] + (e['to'] - e['from']) * ease(e['ease'], (t - e['t']) / e['dur'])
    return val


def split_choreo(main_lines, windows, spb, rnd):
    """「一根线突然分裂成两根」（老板 09-07）：副线从主线此刻的姿态**原地长出来**，0.5 秒内分开（角度错开 ±22°、往上挪），
    窗口结束前 0.5 秒又合回主线的姿态再淡掉。两根都能校准（音符按 assign_lines 分配）。"""
    out = []
    for (a, b) in windows:
        p0 = pose_at(main_lines, a); p1 = pose_at(main_lines, b)
        d = rnd.choice([22.0, -22.0])
        out += [dict(t=round(a, 3), dur=0.0, op='rotate', **{'from': p0['rotate'], 'to': p0['rotate']}, ease='linear'),
                dict(t=round(a, 3), dur=0.0, op='move_x', **{'from': p0['move_x'], 'to': p0['move_x']}, ease='linear'),
                dict(t=round(a, 3), dur=0.0, op='move_y', **{'from': p0['move_y'], 'to': p0['move_y']}, ease='linear'),
                dict(t=round(a + 0.25, 3), dur=0.5, op='rotate', **{'from': p0['rotate'], 'to': p0['rotate'] + d}, ease='cubicInOut'),
                dict(t=round(a + 0.25, 3), dur=0.5, op='move_y', **{'from': p0['move_y'], 'to': p0['move_y'] - 0.30}, ease='cubicInOut'),
                dict(t=round(b - 0.7, 3), dur=0.5, op='rotate', **{'from': p0['rotate'] + d, 'to': p1['rotate']}, ease='cubicInOut'),
                dict(t=round(b - 0.7, 3), dur=0.5, op='move_x', **{'from': p0['move_x'], 'to': p1['move_x']}, ease='cubicInOut'),
                dict(t=round(b - 0.7, 3), dur=0.5, op='move_y', **{'from': p0['move_y'] - 0.30, 'to': p1['move_y']}, ease='cubicInOut')]
    return sorted(out, key=lambda e: (e['t'], e['op']))


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
            lines_k = split_choreo(main_lines, [(f - 0.4, to + 0.4)], a['spb'], rnd) if line == 1 else side_choreo(line, [(f, to)], a['spb'], rnd)
            judges.append(dict(**{'from': round(f - 0.4, 3), 'to': round(to + 0.4, 3)}, lines=lines_k))
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
