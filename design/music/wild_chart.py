"""「癫狂」难度（老板 2026-09-07 傍晚的 I Wanna 视频：视角突然拉伸、音符忽大忽小、一直闪屏、背景硬切，视觉冲击力）。

不改音符：拿现成的 hard 谱（units 一样）叠一层舞台特效关键帧 `stage`（格式见 level-design-guide §1 / Rhythm.FxEvent）。
按能量分段（8 小节一段）编排：
- 每段开头背景硬切（1 网点 / 2 射线轮 / 3 螺旋点阵 / 4 纯色，轮着来；最安静的段回到 0 底光）；
- 每小节强拍 zoom 1 → 1.12 → 1（一拍内），能量高的段每两拍一次；
- 段与段之间视角拉伸：sx 1.28 / sy 0.86 或反过来，1 拍过渡，保持整段；
- 能量最高的两段：整段慢转 ±8°、每小节强拍抖一下（0.012 屏高，0.3 秒）、**闪屏**：每半拍白闪 0.55（0.05 秒亮 0.1 秒灭），减弱动效的用户 App 侧不闪；
- 音符缩放：副歌每拍 1.0 → 1.35 → 1.0，主歌不动；
- 底光色相每段偏 60°。
用法：python3 wild_chart.py <hard 谱路径> [--out 目录]  → 同目录 chart_<id>_wild.json
"""
import argparse, json, os, sys

import numpy as np


def keys(t, op, frm, to, dur, ease='cubicInOut'):
    return dict(t=round(t, 3), dur=round(dur, 3), op=op, **{'from': round(frm, 4), 'to': round(to, 4)}, ease=ease)


def build_stage(chart):
    bpm = chart['bpm']; spb = 60.0 / bpm; bar = 4 * spb
    notes = chart['notes']
    t0 = min(n['t'] for n in notes); t1 = max(n['t'] + n.get('dur', 0) for n in notes)
    phase = t0 - int(t0 / bar) * bar
    energy = chart.get('energy', {}).get('v') or []
    hz = chart.get('energy', {}).get('hz', 4)
    def e_of(a, b):
        if not energy: return 0.7
        i0, i1 = int(a * hz), int(b * hz)
        return float(np.mean(energy[max(0, i0):max(i0 + 1, i1)]))
    n_bars = int((t1 - phase) / bar) + 1
    segs = [(b, min(n_bars, b + 8)) for b in range(0, n_bars, 8)]
    es = [e_of(phase + a * bar, phase + b * bar) for a, b in segs]
    top2 = set(sorted(range(len(segs)), key=lambda i: -es[i])[:2])
    quiet = set(sorted(range(len(segs)), key=lambda i: es[i])[:max(1, len(segs) // 4)])
    st = []
    bg_cycle = [1, 2, 3, 4, 2, 1, 3]
    cur_sx, cur_sy, cur_hue = 1.0, 1.0, 0.0
    for i, (a, b) in enumerate(segs):
        ta = phase + a * bar
        if ta < t0 - 0.5: ta = t0 - 0.5
        hot = i in top2
        # 背景硬切
        bg = 0 if i in quiet else bg_cycle[i % len(bg_cycle)]
        st.append(keys(ta, 'bg', bg, bg, 0.0, 'linear'))
        # 视角拉伸：段间换一次
        sx, sy = ((1.28, 0.86) if i % 2 == 0 else (0.86, 1.22)) if i not in quiet else (1.0, 1.0)
        st.append(keys(ta, 'sx', cur_sx, sx, spb)); st.append(keys(ta, 'sy', cur_sy, sy, spb)); cur_sx, cur_sy = sx, sy
        # 色相
        hue = (cur_hue + 60.0) % 360
        st.append(keys(ta, 'hue', cur_hue, hue, spb * 2)); cur_hue = hue
        # 慢转（副歌）
        if hot:
            st.append(keys(ta, 'spin', 0.0, 8.0 if i % 2 == 0 else -8.0, 2 * bar))
            st.append(keys(phase + (b - 2) * bar, 'spin', 8.0 if i % 2 == 0 else -8.0, 0.0, 2 * bar))
        # 逐小节
        for bb in range(a, b):
            tb = phase + bb * bar
            if tb < t0 - 0.3: continue
            # zoom 踩拍
            step = 2 * spb if hot or es[i] > 0.7 else 4 * spb
            tt = tb
            while tt < tb + bar - 1e-3:
                st.append(keys(tt, 'zoom', 1.0, 1.12, spb * 0.25, 'easeOut')); st.append(keys(tt + spb * 0.25, 'zoom', 1.12, 1.0, spb * 0.6))
                tt += step
            if hot:
                st.append(keys(tb, 'shake', 0.0, 0.012, 0.0, 'linear')); st.append(keys(tb + 0.3, 'shake', 0.012, 0.0, 0.0, 'linear'))
                # 闪屏：每半拍一下
                tt = tb
                while tt < tb + bar - 1e-3:
                    st.append(keys(tt, 'flash', 0.0, 0.55, 0.0, 'linear')); st.append(keys(tt + 0.05, 'flash', 0.55, 0.0, 0.10, 'linear'))
                    tt += spb / 2
                # 音符忽大忽小
                tt = tb
                while tt < tb + bar - 1e-3:
                    st.append(keys(tt, 'notes', 1.0, 1.35, spb * 0.3, 'easeOut')); st.append(keys(tt + spb * 0.3, 'notes', 1.35, 1.0, spb * 0.5))
                    tt += spb
    # 结尾归零
    st.append(keys(t1 + 0.5, 'sx', cur_sx, 1.0, 1.0)); st.append(keys(t1 + 0.5, 'sy', cur_sy, 1.0, 1.0)); st.append(keys(t1 + 0.5, 'bg', 0, 0, 0.0, 'linear'))
    return sorted(st, key=lambda e: (e['t'], e['op']))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('hard'); ap.add_argument('--out', default=None)
    args = ap.parse_args()
    c = json.load(open(args.hard))
    assert c['difficulty'] == 'hard', '要拿 hard 谱当底'
    c['difficulty'] = 'wild'
    c['stage'] = build_stage(c)
    out = args.out or os.path.dirname(os.path.abspath(args.hard))
    p = os.path.join(out, f"chart_{c['song']}_wild.json")
    json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
    ops = {}
    for e in c['stage']: ops[e['op']] = ops.get(e['op'], 0) + 1
    print(f"{c['zh']} wild：{len(c['notes'])} 音符（同 hard），stage {len(c['stage'])} 帧 {ops} → {p}")


if __name__ == '__main__':
    main()
