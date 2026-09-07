#!/usr/bin/env python3
"""从**音频**出谱面（外来曲子用；自家合成的曲子走 make_songs.py 从乐谱导）。

用法：python3 chart_from_audio.py <song_id> <中文名> <音频.ogg> [--bpm 158] [--out assets/charts]

做法（librosa）：
  1. 拍点：已知 BPM 就按它锁；不知道就 beat_track 估。拍点是网格，音符只落在拍网格（easy：拍；hard：半拍）上，
     这样谱面"跟着音乐"而不是跟着噪声。
  2. 起音：onset_strength 在每个网格点上的强度 → 超过阈值才放音符（hard 阈值低、easy 阈值高）。
  3. 轨道：那一拍的**频谱质心**（高 → 右边轨），连续的音尽量不跳太远（旋律感）。
  4. 种类（跟 make_songs.py 一套规矩，老板定的四种）：
       slide  —— 长音：起音之后连着几拍能量不掉、又没有新起音 → 按住（≥1.45 拍）；
                 **slide 按住期间不出任何别的块**（老板规则），生成时直接把窗口里的其它音符删掉
       swipe  —— 乐句收尾（后面空 ≥1 拍）→ 左右滑，方向按质心走向；有配额
       trace  —— hard 才有：跨 ≥2 轨且挨得近的后一个，有配额；不需要点，落线时按着就算
       tick   —— 其余
  5. 编舞：按段落（能量突变 / 每 8 小节）放关键帧：小节线轻沉、段落侧转、副歌前立一次竖线或翻面（hard 才翻）。
  6. approach：easy 1.7s / hard 1.45s（谱面参数，老板：按关卡和难度定）。

输出：chart_<id>_easy.json / chart_<id>_hard.json（格式见 design/rhythm-spec.md §6.2）+ 打印 units 供 charts-meta 用。
"""
import argparse, json, math, os, sys
import numpy as np
import librosa

SR = 22050


def analyze(path, bpm=None):
    y, sr = librosa.load(path, sr=SR, mono=True)
    onset_env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=512)
    if bpm:
        tempo = float(bpm)
        _, beats = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr, hop_length=512, bpm=tempo, units='time')
    else:
        tempo, beats = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr, hop_length=512, units='time')
        tempo = float(np.atleast_1d(tempo)[0])
    beats = np.asarray(beats, dtype=float)
    if len(beats) < 8:
        sys.exit(f'拍点太少（{len(beats)}），这首不适合自动出谱')
    # 用拍点拟合一条等间隔网格（BPM 固定的歌，librosa 的拍点会有抖动，拟合后更整齐）
    spb = 60.0 / tempo
    k = np.arange(len(beats))
    phase = np.median(beats - k * spb)                 # 第 0 拍的时刻
    grid_beats = phase + k * spb
    # 拍上的起音强度、频谱质心（决定轨道）、RMS（决定长音）
    times = librosa.times_like(onset_env, sr=sr, hop_length=512)
    cent = librosa.feature.spectral_centroid(y=y, sr=sr, hop_length=512)[0]
    rms = librosa.feature.rms(y=y, hop_length=512)[0]
    def at(arr, t, win=0.05):
        i = np.searchsorted(times, t)
        lo, hi = max(0, i - int(win * sr / 512)), min(len(arr), i + int(win * sr / 512) + 1)
        return float(arr[lo:hi].max()) if hi > lo else 0.0
    dur = len(y) / sr
    return dict(tempo=tempo, spb=spb, phase=phase, dur=dur,
                onset=lambda t: at(onset_env, t), cent=lambda t: at(cent, t, 0.08), rms=lambda t: at(rms, t, 0.08),
                onset_max=float(onset_env.max()))


def build(a, hard, song_id, zh):
    spb, phase, dur = a['spb'], a['phase'], a['dur']
    step = spb / 2 if hard else spb                     # hard 半拍网格，easy 整拍
    grid = []
    t = phase + 2 * spb                                 # 头两拍留给人反应
    while t < dur - 1.5:
        grid.append(t); t += step
    # 1) 候选音符：网格点上起音够强。阈值用**网格点上强度的分位数**（easy 留最强的 45%，hard 留 62%），
    #    不用全局最大值 —— 最大值常是一记孤立的鼓点，按它的比例卡，整首歌一个音都过不了。
    strengths = np.array([a['onset'](t) for t in grid])
    thr = float(np.quantile(strengths, 0.38 if hard else 0.55))
    cand = [(t, a['onset'](t), a['cent'](t)) for t in grid]
    cand = [(t, o, c) for t, o, c in cand if o >= thr and o > 0]
    if not cand:
        sys.exit('一个起音都没抓到，阈值不对')
    # 2) 轨道：质心分位数分四档，再做"别跳太远"的平滑
    cs = np.array([c for _, _, c in cand])
    q = np.quantile(cs, [0.25, 0.5, 0.75])
    notes = []
    prev_lane = 1
    for t, o, c in cand:
        lane = int(np.searchsorted(q, c))
        if abs(lane - prev_lane) > 2: lane = prev_lane + (1 if lane > prev_lane else -1)   # 一步最多跨两轨
        notes.append([round(t, 3), lane, o])
        prev_lane = lane
    # 3) 密度控制：最小间隔（easy 一拍 / hard 半拍），连着太密的抽稀（留起音强的）
    min_gap = step * 0.98
    thinned = []
    for n in notes:
        if thinned and n[0] - thinned[-1][0] < min_gap:
            if n[2] > thinned[-1][2]: thinned[-1] = n
            continue
        thinned.append(n)
    notes = thinned
    # 4) 种类
    out = []
    n = len(notes)
    quota = {'trace': int(n * 0.10) if hard else 0, 'swipe': int(n * (0.10 if hard else 0.06)), 'slide': int(n * 0.10)}
    used = {'trace': 0, 'swipe': 0, 'slide': 0}
    last_kind = None
    i = 0
    hold_until = -1.0
    while i < n:
        t, lane, o = notes[i]
        if t < hold_until:                              # 老板规则：slide 按住期间不出任何别的块
            i += 1; continue
        prev = out[-1] if out else None
        nxt = notes[i + 1] if i + 1 < n else None
        kind, d, dr = 'tick', 0.0, 0
        # 长音：接下来 2 拍里能量不掉（rms 保持 ≥ 起音时的 70%）且没有强起音 → slide
        # 长音要三个条件都满足：后面 1.5 拍能量不掉、没有新起音、这一拍本身是段落里较强的起音；再加配额（≤10%）
        sustained = all(a['rms'](t + k * spb / 2) >= a['rms'](t) * 0.8 and a['onset'](t + k * spb / 2) < thr
                        for k in range(1, 4)) and o >= thr * 1.3
        if sustained and used['slide'] < quota['slide'] and (nxt is None or nxt[0] - t >= 1.45 * spb) and last_kind != 'slide':
            kind, d = 'slide', round(1.5 * spb, 3); used['slide'] += 1
            hold_until = t + d + 0.15                    # 尾巴过线后再留 0.15s
        else:
            gap_after = (nxt[0] - t) / spb if nxt else 99
            if gap_after >= 1.0 and used['swipe'] < quota['swipe'] and last_kind != 'swipe':
                dr = 1 if (prev is None or lane >= prev['lane']) else -1
                kind = 'swipe'; used['swipe'] += 1
            elif (hard and prev and 0 < t - prev['t'] <= 1.1 * spb and abs(prev['lane'] - lane) >= 2
                  and used['trace'] < quota['trace'] and last_kind != 'trace'):
                kind = 'trace'
                used['trace'] += 1
        out.append(dict(t=t, lane=lane, type=kind, dur=d, dir=dr))
        last_kind = kind
        i += 1
    return dict(song=song_id, zh=zh, bpm=int(round(a['tempo'])), difficulty='hard' if hard else 'easy',
                offset=0, approach=1.45 if hard else 1.7, notes=out, lines=choreo(a, hard, song_id))


def choreo(a, hard, song_id='song'):
    """编舞见 choreo.py（老板 09-07：翻转 / 上下左右移动 / 倾斜 / 持久竖线）。"""
    from choreo import choreo as _choreo
    return _choreo(song_id, a['spb'], a['phase'], a['dur'], hard)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('song_id'); ap.add_argument('zh'); ap.add_argument('audio')
    ap.add_argument('--bpm', type=float, default=None); ap.add_argument('--out', default='.')
    args = ap.parse_args()
    a = analyze(args.audio, args.bpm)
    os.makedirs(args.out, exist_ok=True)
    meta = []
    for hard in (False, True):
        c = build(a, hard, args.song_id, args.zh)
        from lanes import spread                                   # 4 轨 → 12 轨（老板 09-07），见 lanes.py
        c['notes'] = spread(c['notes'], args.song_id + c['difficulty'])
        p = os.path.join(args.out, f'chart_{args.song_id}_{c["difficulty"]}.json')
        with open(p, 'w') as f:
            json.dump(c, f, ensure_ascii=False, separators=(',', ':'))
        ns = c['notes']
        cnt = {k: sum(1 for x in ns if x['type'] == k) for k in ('tick', 'slide', 'trace', 'swipe')}
        units = len(ns) + cnt['slide']
        meta.append(dict(id=f'{args.song_id}_{c["difficulty"]}', song=args.song_id, zh=args.zh, difficulty=c['difficulty'],
                         notes=len(ns), ticks=cnt['tick'], slides=cnt['slide'], traces=cnt['trace'], swipes=cnt['swipe'],
                         units=units, durationMs=int(a['dur'] * 1000)))
        print(f'{args.zh} {c["difficulty"]:4} bpm={c["bpm"]} 音符 {len(ns):3}（tick {cnt["tick"]} slide {cnt["slide"]} '
              f'trace {cnt["trace"]} swipe {cnt["swipe"]}） units={units} 编舞 {len(c["lines"])} 帧 → {p}')
    with open(os.path.join(args.out, f'meta_{args.song_id}.json'), 'w') as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
