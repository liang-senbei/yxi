"""4 轨 → 12 轨（老板 2026-09-07：「不一定只有 4 条，手机总长度除以音符长度能放几条就放几条」→ App 里 Rhythm.LANES = 12）。

规则（只改 lane，不改音符数 / 时刻 / 种类 → units 不变，服务端不用动）：
- 基准：老轨 k → 新轨 3k+1（四个中心 1 / 4 / 7 / 10），再按曲子 id 播种抖动 {-1, 0, +1}，让同一老轨的音符散开成一片而不是一条线。
- 同时出现（|Δt| < 0.08 秒）的音符至少隔 2 条（手指宽），不够就把后来的往外挪一格。
- 连续两个音符（|Δt| < 0.20 秒）落点相差 ≤ 6 条，别让手飞。
- slide / trace 是一个音符一条轨，跟着走即可。
"""
import random

LANES = 12


def spread(notes, seed):
    rnd = random.Random(f'lanes-{seed}')
    out = sorted(notes, key=lambda n: n['t'])
    placed = []                                  # (t, lane)
    for n in out:
        base = n['lane'] * 3 + 1 if n['lane'] < 4 else n['lane']
        cand = [base + d for d in (-1, 0, 1)]
        rnd.shuffle(cand)
        lane = None
        for c in cand:
            if not 0 <= c < LANES:
                continue
            if any(abs(t - n['t']) < 0.08 and abs(l - c) < 2 for t, l in placed):
                continue
            if any(abs(t - n['t']) < 0.20 and abs(l - c) > 6 for t, l in placed):
                continue
            lane = c
            break
        if lane is None:                          # 三个候选都不行：往外找最近的空位
            for d in range(2, LANES):
                for c in (base - d, base + d):
                    if 0 <= c < LANES and not any(abs(t - n['t']) < 0.08 and abs(l - c) < 2 for t, l in placed) \
                            and not any(abs(t - n['t']) < 0.20 and abs(l - c) > 6 for t, l in placed):   # 回退也守「别让手飞」（审查）
                        lane = c; break
                if lane is not None:
                    break
        n['lane'] = lane if lane is not None else max(0, min(LANES - 1, base))
        placed.append((n['t'], n['lane']))
        placed = [(t, l) for t, l in placed if n['t'] - t < 0.25]
    return out


if __name__ == '__main__':
    ns = [dict(t=i * 0.1, lane=i % 4, type='tick') for i in range(40)] + [dict(t=1.0, lane=1, type='tick'), dict(t=1.0, lane=2, type='tick')]
    r = spread(ns, 'selftest')
    assert all(0 <= n['lane'] < LANES for n in r)
    same = [n for n in r if abs(n['t'] - 1.0) < 1e-6]
    assert len(same) == 3 and all(abs(a['lane'] - b['lane']) >= 2 for a in same for b in same if a is not b), same
    print('lanes 自检 OK', sorted(n['lane'] for n in same))
