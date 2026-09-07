"""给每张谱写入曲子的能量包络 `energy`（老板 2026-09-07：「随着歌曲进入高潮，背景更亮、色彩层次更足」）。

energy = {"hz": 4, "v": [0..1 …]}：每 0.25 秒一个值，RMS 取 dB 后按 5%~95% 分位归一，再 0.75 秒平滑。
App 侧 `Chart.energyAt(t)` 线性插值，喂给 StageGlow（亮度、色相跨度、多两团光）。
音频统一用 App 里打包的 res/raw/yx_<id>.ogg（8 首都有），不依赖 incoming/。
用法：python3 energy_all.py
"""
import glob, json, os
import numpy as np
import librosa

here = os.path.dirname(os.path.abspath(__file__))
raw = os.path.join(here, '../../android/app/src/main/res/raw')
charts = os.path.join(here, '../../android/app/src/main/assets/charts')
HZ = 4


def envelope(path):
    y, sr = librosa.load(path, sr=22050, mono=True)
    hop = sr // HZ
    rms = librosa.feature.rms(y=y, frame_length=hop * 2, hop_length=hop)[0]
    # 线性 RMS 按 95% 分位归一再开 1.5 次方：主歌 0.3~0.6、副歌 0.9~1，dB 归一会把整首压成 0.8+（第一版踩的）
    v = np.clip(rms / max(1e-6, np.percentile(rms, 95)), 0, 1) ** 1.5
    k = 3                                                 # 0.75 秒滑动平均
    v = np.convolve(v, np.ones(k) / k, mode='same')
    return [round(float(x), 2) for x in v]


if __name__ == '__main__':
    cache = {}
    for p in sorted(glob.glob(os.path.join(charts, 'chart_*.json'))):
        c = json.load(open(p))
        sid = c['song']
        if sid not in cache:
            cache[sid] = envelope(os.path.join(raw, f'yx_{sid}.ogg'))
        v = cache[sid]
        c['energy'] = {'hz': HZ, 'v': v}
        json.dump(c, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
        peak = max(range(len(v)), key=lambda i: v[i]) / HZ
        print(f"{os.path.basename(p):26} energy {len(v)} 点，最高在 {peak:.0f}s，均值 {np.mean(v):.2f}")
