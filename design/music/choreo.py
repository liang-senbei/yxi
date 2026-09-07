"""判定线编舞（老板 2026-09-07 重申：线要**翻转、上下左右移动、倾斜、立成持久的竖线**，音符永远垂直于线）。

生成的是谱面 JSON 里的 `lines` 关键帧：{t, dur, op(rotate|move_x|move_y), from, to, ease}。
App 侧 `Chart.poseAt()` 按 op 各取「最近开始的那条」的 valueAt(now)，所以每个 op 的关键帧必须首尾相接
（下一条的 from == 上一条的 to），这里用 `_Track` 保证。

结构：
- 每 2 小节一次小节线轻沉（move_y 0.02，60ms 下 340ms 回）。
- 以 8 小节为一个「段」，每段进一个「场景」：平 / 倾 / 升 / 横移 / **立竖**（保持整段）/ **翻面**（hard）。
  场景顺序按曲子 id 播种的随机序列，保证同一首每次生成一样、不同曲子不一样。
- 过渡用 2 拍（0.7~1.6 秒）cubicInOut，落在段首的强拍上。
- 竖线时线心上移（dy=-0.30），翻面时线心上移到 0.22H 处（dy=-0.56）——让音符从屏幕另一边飞过来，线还在屏幕里。

easy：幅度 ×0.7、竖线只立一次、不翻面；hard：全套。
"""
import random

SCENES = {
    'flat':     dict(deg=0.0,   dx=0.0,   dy=0.0),
    'tiltL':    dict(deg=-13.0, dx=0.06,  dy=0.0),
    'tiltR':    dict(deg=13.0,  dx=-0.06, dy=0.0),
    'rise':     dict(deg=0.0,   dx=0.0,   dy=-0.26),
    'slideL':   dict(deg=-6.0,  dx=-0.12, dy=-0.06),
    'slideR':   dict(deg=6.0,   dx=0.12,  dy=-0.06),
    'vertical': dict(deg=90.0,  dx=0.0,   dy=-0.30),
    'vertNeg':  dict(deg=-90.0, dx=0.0,   dy=-0.30),
    'flip':     dict(deg=180.0, dx=0.0,   dy=-0.56),
}


class _Track:
    """一个 op 的关键帧序列，保证首尾相接。"""
    def __init__(self, op):
        self.op, self.cur, self.out = op, 0.0, []

    def to(self, t, dur, value, ease='cubicInOut'):
        if abs(value - self.cur) < 1e-6:
            return
        self.out.append(dict(t=round(t, 3), dur=round(dur, 3), op=self.op, **{'from': round(self.cur, 4), 'to': round(value, 4)}, ease=ease))
        self.cur = value


def choreo(song_id, spb, phase, dur, hard):
    rnd = random.Random(f'{song_id}-{"hard" if hard else "easy"}')
    bars = int((dur - phase) / (4 * spb))
    rot, mx, my = _Track('rotate'), _Track('move_x'), _Track('move_y')
    amp = 1.0 if hard else 0.7
    trans = min(1.6, max(0.7, 2 * spb))                       # 过渡 2 拍

    # 场景序列：每 8 小节一段（短曲 4 小节，不然一分钟的曲子轮不到立竖）；第一段平；竖线 / 翻面放在中后段
    seg = 8 if bars >= 48 else 4
    n_seg = max(1, (bars - 2) // seg)
    pool = ['tiltL', 'tiltR', 'rise', 'slideL', 'slideR']
    seq = ['flat']
    last = 'flat'
    for _ in range(1, n_seg):
        c = rnd.choice([p for p in pool if p != last])
        seq.append(c); last = c
    # 立竖：easy 一次、hard 两次（其中一次可能是反向）；翻面：只 hard，一次
    specials = []
    if n_seg >= 3:
        specials.append('vertical')
        if hard and n_seg >= 5:
            specials.append(rnd.choice(['vertical', 'vertNeg']))
        if hard and n_seg >= 4:
            specials.append('flip')
    slots = list(range(2, n_seg)) if n_seg > 2 else []
    rnd.shuffle(slots)
    for s, slot in zip(specials, slots):
        seq[slot] = s

    # 逐段写关键帧
    for i, name in enumerate(seq):
        t = phase + (2 + i * seg) * 4 * spb                   # 段首强拍（前 2 小节留给开场）
        sc = SCENES[name]
        k = 1.0 if name in ('vertical', 'vertNeg', 'flip') else amp
        deg = sc['deg'] * k
        # 翻面来回都走同一个方向（0→180→0 再翻回来会转一整圈，别）
        rot.to(t, trans if name not in ('flip',) else trans * 1.5, deg)
        mx.to(t, trans, sc['dx'] * k)
        my.to(t, trans, sc['dy'] * (1.0 if name in ('vertical', 'vertNeg', 'flip') else k))

    # 小节线轻沉（叠在 my 之上：用当前值为基线，沉一下回来）
    dips = []
    for bar in range(2, bars - 1, 2):
        t = phase + bar * 4 * spb
        base = _value_at(my.out, t)
        dips.append(dict(t=round(t, 3), dur=0.06, op='move_y', **{'from': round(base, 4), 'to': round(base + 0.02, 4)}, ease='easeOut'))
        dips.append(dict(t=round(t + 0.06, 3), dur=0.34, op='move_y', **{'from': round(base + 0.02, 4), 'to': round(base, 4)}, ease='cubicInOut'))
    # 轻沉不能落在过渡里（会把过渡的 from/to 链打断）
    busy = [(e['t'], e['t'] + e['dur']) for e in my.out]
    dips = [d for d in dips if not any(a - 0.05 <= d['t'] <= b + 0.45 for a, b in busy)]

    out = rot.out + mx.out + my.out + dips
    return sorted(out, key=lambda e: (e['t'], e['op']))


def _value_at(track, t):
    """某 op 的关键帧序列在 t 时刻的稳态值（过渡结束后的 to；过渡中取 to，调用方已避开过渡）。"""
    v = 0.0
    for e in track:
        if e['t'] <= t:
            v = e['to']
    return v


if __name__ == '__main__':
    # 自检：关键帧首尾相接、竖线/翻面各自出现、easy 不翻面
    for hard in (False, True):
        ks = choreo('selftest', 60 / 128, 0.3, 120.0, hard)
        for op in ('rotate', 'move_x', 'move_y'):
            seq = [e for e in ks if e['op'] == op]
            for a, b in zip(seq, seq[1:]):
                assert abs(a['to'] - b['from']) < 1e-6, (op, a, b)
        degs = {e['to'] for e in ks if e['op'] == 'rotate'}
        assert 90.0 in degs or -90.0 in degs, degs
        assert (180.0 in degs) == hard, degs
    print('choreo 自检 OK')
