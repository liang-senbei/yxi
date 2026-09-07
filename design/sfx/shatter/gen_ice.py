#!/usr/bin/env python3
"""冰/陶瓷/石 碎裂音效家族 — 5 个 WAV，纯 numpy+scipy 合成。

运行: python3 gen_ice.py
输出: ice_1.wav … ice_5.wav + manifest_ice.json
"""
import json, struct, pathlib, sys
import numpy as np
from scipy.signal import butter, sosfilt

SR = 44100
HERE = pathlib.Path(__file__).resolve().parent
RNG = np.random.default_rng(42)

# ── helpers ──────────────────────────────────────────────────────────────

def fade_in(sig, ms=3):
    n = int(SR * ms / 1000)
    sig[:n] *= np.linspace(0, 1, n)
    return sig

def fade_out(sig, ms=40):
    n = min(int(SR * ms / 1000), len(sig))
    sig[-n:] *= np.linspace(1, 0, n)
    return sig

def normalize_dbfs(sig, db=-1.0):
    peak = np.max(np.abs(sig))
    if peak < 1e-10:
        return sig
    target = 10 ** (db / 20)
    return sig * (target / peak)

def bandpass(sig, lo, hi, order=4):
    lo = max(lo, 20)
    hi = min(hi, SR // 2 - 1)
    if lo >= hi:
        return sig
    sos = butter(order, [lo, hi], btype='band', fs=SR, output='sos')
    return sosfilt(sos, sig)

def lowpass(sig, freq, order=4):
    freq = min(freq, SR // 2 - 1)
    sos = butter(order, freq, btype='low', fs=SR, output='sos')
    return sosfilt(sos, sig)

def highpass(sig, freq, order=2):
    freq = max(freq, 20)
    sos = butter(order, freq, btype='high', fs=SR, output='sos')
    return sosfilt(sos, sig)

def exp_decay(n, tau):
    """指数衰减包络，tau 是时间常数(秒)"""
    t = np.arange(n) / SR
    return np.exp(-t / tau)

def crack_transient(dur_ms=15, freq_lo=800, freq_hi=4500, resonances=None):
    """裂纹瞬态：极短带通噪声 + 共振峰"""
    n = int(SR * dur_ms / 1000)
    noise = RNG.standard_normal(n)
    sig = bandpass(noise, freq_lo, freq_hi, order=3)
    sig *= exp_decay(n, dur_ms / 1000 * 0.4)
    # 共振峰叠加
    if resonances:
        t = np.arange(n) / SR
        for freq, amp, decay in resonances:
            sig += amp * np.sin(2 * np.pi * freq * t) * np.exp(-t / decay)
    return sig

def particles(count, total_dur_s, freq_range=(1500, 6000), decay_range=(0.003, 0.012)):
    """碎片散落：随机时间点的短促小粒子"""
    n_total = int(SR * total_dur_s)
    out = np.zeros(n_total)
    for _ in range(count):
        onset = int(RNG.uniform(0.005, total_dur_s * 0.85) * SR)
        freq = RNG.uniform(*freq_range)
        dur = RNG.uniform(*decay_range)
        n_p = min(int(dur * SR * 8), n_total - onset)
        if n_p < 10:
            continue
        t = np.arange(n_p) / SR
        grain = RNG.standard_normal(n_p)
        grain = bandpass(grain, freq * 0.7, min(freq * 1.4, SR // 2 - 1), order=2)
        amp = RNG.uniform(0.1, 0.5)
        grain *= amp * np.exp(-t / dur)
        out[onset:onset + n_p] += grain
    # 整体渐弱包络
    out *= exp_decay(n_total, total_dur_s * 0.45)
    return out

def resonant_ping(freq, dur_s=0.06, decay_s=0.025, amp=0.6):
    """共振短音——陶瓷/冰碰撞时的音高感"""
    n = int(SR * dur_s)
    t = np.arange(n) / SR
    sig = amp * np.sin(2 * np.pi * freq * t) * np.exp(-t / decay_s)
    # 加一点谐波
    sig += amp * 0.3 * np.sin(2 * np.pi * freq * 2.17 * t) * np.exp(-t / (decay_s * 0.6))
    sig += amp * 0.15 * np.sin(2 * np.pi * freq * 3.41 * t) * np.exp(-t / (decay_s * 0.4))
    return sig

def write_wav(path, sig):
    """16-bit PCM mono WAV"""
    sig = np.clip(sig, -1.0, 1.0)
    data = (sig * 32767).astype(np.int16)
    raw = data.tobytes()
    with open(path, 'wb') as f:
        f.write(b'RIFF')
        f.write(struct.pack('<I', 36 + len(raw)))
        f.write(b'WAVE')
        f.write(b'fmt ')
        f.write(struct.pack('<IHHIIHH', 16, 1, 1, SR, SR * 2, 2, 16))
        f.write(b'data')
        f.write(struct.pack('<I', len(raw)))
        f.write(raw)


# ── 5 个音效 ──────────────────────────────────────────────────────────

def make_ice_1():
    """冰裂 — 清脆高频裂纹 + 冰粒散落"""
    crack = crack_transient(12, 1200, 5500,
                            resonances=[(2200, 0.7, 0.008), (3800, 0.4, 0.005)])
    tail_dur = 0.35
    tail = particles(35, tail_dur, freq_range=(2000, 7000), decay_range=(0.003, 0.010))
    # 冰的特征：加一点高频闪烁
    n_tail = len(tail)
    shimmer = bandpass(RNG.standard_normal(n_tail), 5000, 9000, order=2)
    shimmer *= exp_decay(n_tail, 0.08) * 0.15
    tail += shimmer
    tail = lowpass(tail, 9000)
    sig = np.concatenate([crack, tail])
    sig = fade_in(sig, 2)
    sig = fade_out(sig, 50)
    return normalize_dbfs(sig, -1.0)

def make_ice_2():
    """薄瓷 — 陶瓷碎：中频共振 + 瓷片叮当"""
    # 陶瓷裂纹更有"咔"感
    crack = crack_transient(18, 600, 3500,
                            resonances=[(1100, 0.8, 0.012), (1800, 0.5, 0.008), (2600, 0.3, 0.005)])
    ping = resonant_ping(1400, dur_s=0.04, decay_s=0.018, amp=0.5)
    n_crack = len(crack)
    n_ping = len(ping)
    if n_ping > n_crack:
        crack = np.pad(crack, (0, n_ping - n_crack))
    else:
        ping = np.pad(ping, (0, n_crack - n_ping))
    onset = crack + ping
    # 瓷片散落——比冰低一些
    tail = particles(28, 0.40, freq_range=(1200, 5000), decay_range=(0.004, 0.014))
    tail = lowpass(tail, 7500)
    sig = np.concatenate([onset, tail])
    sig = fade_in(sig, 3)
    sig = fade_out(sig, 60)
    return normalize_dbfs(sig, -1.0)

def make_ice_3():
    """青瓦 — 低沉石质碎裂 + 沉闷碎片"""
    crack = crack_transient(22, 300, 2200,
                            resonances=[(500, 0.9, 0.015), (900, 0.5, 0.010)])
    # 石头裂开的沉闷低频冲击
    n_thud = int(SR * 0.03)
    t = np.arange(n_thud) / SR
    thud = 0.6 * np.sin(2 * np.pi * 180 * t) * np.exp(-t / 0.012)
    thud += 0.3 * np.sin(2 * np.pi * 350 * t) * np.exp(-t / 0.008)
    n_crack = len(crack)
    if len(thud) > n_crack:
        crack = np.pad(crack, (0, len(thud) - n_crack))
    else:
        thud = np.pad(thud, (0, n_crack - len(thud)))
    onset = crack + thud
    # 石质碎片——低频、颗粒大
    tail = particles(20, 0.45, freq_range=(600, 3500), decay_range=(0.006, 0.018))
    tail = lowpass(tail, 5500)
    sig = np.concatenate([onset, tail])
    sig = fade_in(sig, 3)
    sig = fade_out(sig, 70)
    return normalize_dbfs(sig, -1.0)

def make_ice_4():
    """岩片 — 脆岩劈裂：双层裂纹 + 锐利碎片"""
    # 两层裂纹：先一声闷裂，紧跟一声脆裂
    crack1 = crack_transient(10, 400, 2000,
                             resonances=[(700, 0.6, 0.008)])
    gap = np.zeros(int(SR * 0.008))  # 8ms 间隔
    crack2 = crack_transient(8, 1500, 6000,
                             resonances=[(3200, 0.5, 0.004), (4500, 0.3, 0.003)])
    onset = np.concatenate([crack1, gap, crack2])
    # 碎片——混合高低
    tail = particles(30, 0.35, freq_range=(1000, 6500), decay_range=(0.003, 0.012))
    # 一点中频共振余韵
    n_tail = len(tail)
    t = np.arange(n_tail) / SR
    ring = 0.12 * np.sin(2 * np.pi * 1600 * t) * np.exp(-t / 0.06)
    tail += ring
    tail = lowpass(tail, 8000)
    sig = np.concatenate([onset, tail])
    sig = fade_in(sig, 2)
    sig = fade_out(sig, 45)
    return normalize_dbfs(sig, -1.0)

def make_ice_5():
    """冻湖 — 冰面龟裂：长裂纹传播 + 远处碎冰回响"""
    # 裂纹传播效果：频率从低到高扫过
    n_crack = int(SR * 0.05)
    t = np.arange(n_crack) / SR
    freq_sweep = 400 + 3000 * (t / t[-1])  # 400→3400 Hz 扫频
    phase = 2 * np.pi * np.cumsum(freq_sweep) / SR
    crack_tone = 0.5 * np.sin(phase) * np.exp(-t / 0.025)
    crack_noise = bandpass(RNG.standard_normal(n_crack), 500, 4000, order=2)
    crack_noise *= exp_decay(n_crack, 0.02)
    crack = crack_tone + crack_noise * 0.6
    # 远处回响——更长的尾巴，粒子更稀疏
    tail = particles(18, 0.55, freq_range=(1500, 5500), decay_range=(0.005, 0.020))
    # 回响感：加一层延迟混响近似
    n_tail = len(tail)
    delay_n = int(SR * 0.035)
    reverb = np.zeros(n_tail)
    reverb[delay_n:] += tail[:-delay_n] * 0.3
    delay_n2 = int(SR * 0.065)
    if delay_n2 < n_tail:
        reverb[delay_n2:] += tail[:-delay_n2] * 0.15
    tail = tail + reverb
    tail = lowpass(tail, 7000)
    # 冰面共鸣低频
    t_tail = np.arange(n_tail) / SR
    ice_hum = 0.08 * np.sin(2 * np.pi * 220 * t_tail) * np.exp(-t_tail / 0.15)
    tail += ice_hum
    sig = np.concatenate([crack, tail])
    sig = fade_in(sig, 4)
    sig = fade_out(sig, 80)
    return normalize_dbfs(sig, -1.0)


# ── 生成 + 自检 ──────────────────────────────────────────────────────

MANIFEST = [
    {"file": "ice_1.wav", "name": "冰裂", "desc": "清脆高频裂纹，冰粒如碎钻散落"},
    {"file": "ice_2.wav", "name": "薄瓷", "desc": "陶瓷碎裂的咔声，带瓷片叮当余韵"},
    {"file": "ice_3.wav", "name": "青瓦", "desc": "低沉石质碎裂，沉闷颗粒感"},
    {"file": "ice_4.wav", "name": "岩片", "desc": "先闷裂再脆裂的双层劈声，锐利碎片"},
    {"file": "ice_5.wav", "name": "冻湖", "desc": "冰面龟裂传播，远处碎冰回响绵长"},
]

MAKERS = [make_ice_1, make_ice_2, make_ice_3, make_ice_4, make_ice_5]

def self_check():
    """读回每个文件，检查规格"""
    import wave
    ok = True
    print("\n── 自检 ──")
    print(f"{'文件':<12} {'时长/s':>7} {'峰值dBFS':>9} {'采样率':>6} {'声道':>4} {'位深':>4} {'合规':>4}")
    print("-" * 55)
    for m in MANIFEST:
        p = HERE / m["file"]
        with wave.open(str(p), 'rb') as w:
            sr = w.getframerate()
            ch = w.getnchannels()
            sw = w.getsampwidth()
            frames = w.readframes(w.getnframes())
        samples = np.frombuffer(frames, dtype=np.int16).astype(float)
        dur = len(samples) / sr
        peak_db = 20 * np.log10(np.max(np.abs(samples)) / 32767 + 1e-20)
        pass_dur = 0.25 <= dur <= 0.65
        pass_peak = -2.0 <= peak_db <= 0.0
        pass_sr = sr == 44100
        pass_ch = ch == 1
        pass_sw = sw == 2
        all_ok = pass_dur and pass_peak and pass_sr and pass_ch and pass_sw
        if not all_ok:
            ok = False
        tag = "OK" if all_ok else "FAIL"
        print(f"{m['file']:<12} {dur:>7.3f} {peak_db:>9.2f} {sr:>6} {ch:>4} {sw*8:>4} {tag:>4}")
    return ok

if __name__ == "__main__":
    for i, (maker, m) in enumerate(zip(MAKERS, MANIFEST)):
        sig = maker()
        path = HERE / m["file"]
        write_wav(str(path), sig)
        print(f"✓ {m['file']}  ({m['name']})")

    # manifest
    mpath = HERE / "manifest_ice.json"
    with open(mpath, 'w', encoding='utf-8') as f:
        json.dump(MANIFEST, f, ensure_ascii=False, indent=2)
    print(f"✓ manifest_ice.json")

    if not self_check():
        sys.exit(1)
    print("\n全部合规 ✓")
