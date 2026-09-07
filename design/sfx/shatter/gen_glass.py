#!/usr/bin/env python3
"""Generate 5 glass/crystal shatter SFX for Yxi rhythm game."""
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter
import json, struct

SR = 44100
TARGET_DBFS = -1.0  # peak normalize to this

def fade(sig, fade_in_ms=3, fade_out_ms=30):
    fi = int(SR * fade_in_ms / 1000)
    fo = int(SR * fade_out_ms / 1000)
    sig[:fi] *= np.linspace(0, 1, fi)
    sig[-fo:] *= np.linspace(1, 0, fo)
    return sig

def normalize(sig):
    peak = np.max(np.abs(sig))
    if peak < 1e-10:
        return sig
    target = 10 ** (TARGET_DBFS / 20)
    return sig * (target / peak)

def to_16bit(sig):
    sig = np.clip(sig, -1, 1)
    return (sig * 32767).astype(np.int16)

def highshelf_rolloff(sig, cutoff=7000, order=2):
    """Gentle high-frequency rolloff above cutoff."""
    b, a = butter(order, cutoff / (SR / 2), btype='low')
    # Mix: 70% filtered + 30% original to keep sparkle but tame harshness
    return 0.7 * lfilter(b, a, sig) + 0.3 * sig

def grain_cloud(n_grains, dur_s, freq_range, decay_range, amp_range=(0.3, 1.0), spread=0.8):
    """Generate a cloud of decaying sinusoidal grains — the 'shards' scattering."""
    length = int(SR * dur_s)
    out = np.zeros(length)
    for _ in range(n_grains):
        freq = np.random.uniform(*freq_range)
        decay = np.random.uniform(*decay_range)
        amp = np.random.uniform(*amp_range)
        start = int(np.random.uniform(0, length * spread * 0.15))  # cluster near impact
        grain_len = min(int(SR * decay), length - start)
        if grain_len <= 0:
            continue
        t = np.arange(grain_len) / SR
        env = np.exp(-t / (decay * 0.3)) * amp
        grain = env * np.sin(2 * np.pi * freq * t + np.random.uniform(0, 2 * np.pi))
        out[start:start + grain_len] += grain
    return out

def noise_burst(dur_s, bandwidth=(2000, 12000), decay_time=0.05):
    """Short filtered noise burst — the 'crack' of impact."""
    length = int(SR * dur_s)
    t = np.arange(length) / SR
    noise = np.random.randn(length)
    env = np.exp(-t / decay_time)
    noise *= env
    lo, hi = bandwidth
    b, a = butter(3, [lo / (SR / 2), min(hi / (SR / 2), 0.99)], btype='band')
    return lfilter(b, a, noise)

def simple_reverb(sig, delays_ms=[11, 23, 37], decays=[0.3, 0.2, 0.1]):
    """Fake reverb via a few delay taps."""
    out = sig.copy()
    for d_ms, dec in zip(delays_ms, decays):
        d = int(SR * d_ms / 1000)
        padded = np.zeros(len(sig))
        padded[d:] = sig[:-d] * dec
        out += padded
    return out


# ── 1. 薄玻璃 (thin glass — bright, short, delicate) ──
def gen_glass_1():
    dur = 0.30
    impact = noise_burst(dur, bandwidth=(3000, 11000), decay_time=0.015)
    shards = grain_cloud(40, dur, freq_range=(3000, 9000), decay_range=(0.04, 0.12))
    sig = impact * 1.2 + shards * 0.8
    sig = highshelf_rolloff(sig, cutoff=8000)
    sig = simple_reverb(sig, delays_ms=[7, 15], decays=[0.15, 0.08])
    return fade(normalize(sig), fade_in_ms=2, fade_out_ms=20)

# ── 2. 水晶 (crystal — resonant, with a clear tonal ring) ──
def gen_glass_2():
    dur = 0.50
    length = int(SR * dur)
    t = np.arange(length) / SR
    # Tonal resonance — two harmonically related tones
    ring = (np.sin(2 * np.pi * 2400 * t) * 0.5 +
            np.sin(2 * np.pi * 3600 * t) * 0.3 +
            np.sin(2 * np.pi * 6000 * t) * 0.15)
    ring *= np.exp(-t / 0.15)
    impact = noise_burst(dur, bandwidth=(2500, 10000), decay_time=0.012)
    shards = grain_cloud(30, dur, freq_range=(4000, 10000), decay_range=(0.06, 0.18))
    sig = impact * 0.8 + ring * 1.0 + shards * 0.5
    sig = highshelf_rolloff(sig, cutoff=9000)
    sig = simple_reverb(sig, delays_ms=[13, 29, 43], decays=[0.25, 0.15, 0.08])
    return fade(normalize(sig), fade_in_ms=3, fade_out_ms=40)

# ── 3. 冰晶 (ice crystal — very bright, tiny shards, fast scatter) ──
def gen_glass_3():
    dur = 0.35
    impact = noise_burst(dur, bandwidth=(4000, 13000), decay_time=0.008)
    # Many tiny fast grains — ice splinters
    shards = grain_cloud(80, dur, freq_range=(5000, 12000), decay_range=(0.02, 0.06),
                         amp_range=(0.2, 0.7), spread=0.5)
    # A few lower 'chunks'
    chunks = grain_cloud(8, dur, freq_range=(1500, 3000), decay_range=(0.03, 0.08),
                         amp_range=(0.4, 0.8))
    sig = impact * 1.0 + shards * 0.9 + chunks * 0.4
    sig = highshelf_rolloff(sig, cutoff=7500)
    sig = simple_reverb(sig, delays_ms=[5, 11, 19], decays=[0.12, 0.08, 0.04])
    return fade(normalize(sig), fade_in_ms=2, fade_out_ms=25)

# ── 4. 碎镜 (shattered mirror — heavy, lower, dramatic) ──
def gen_glass_4():
    dur = 0.55
    length = int(SR * dur)
    t = np.arange(length) / SR
    # Heavier impact — lower bandwidth
    impact = noise_burst(dur, bandwidth=(800, 6000), decay_time=0.025)
    # Large shard grains — lower freqs, slower decay
    shards = grain_cloud(25, dur, freq_range=(1200, 5000), decay_range=(0.08, 0.25),
                         amp_range=(0.4, 1.0))
    # Some high tinkle on top
    tinkle = grain_cloud(20, dur, freq_range=(5000, 9000), decay_range=(0.03, 0.08),
                         amp_range=(0.15, 0.4))
    # Sub-thud
    thud = np.sin(2 * np.pi * 150 * t) * np.exp(-t / 0.03) * 0.5
    sig = impact * 1.0 + shards * 0.8 + tinkle * 0.4 + thud * 0.6
    sig = highshelf_rolloff(sig, cutoff=6500)
    sig = simple_reverb(sig, delays_ms=[17, 31, 53], decays=[0.3, 0.2, 0.12])
    return fade(normalize(sig), fade_in_ms=3, fade_out_ms=50)

# ── 5. 琉璃 (colored glass — warm, musical, mid-freq beauty) ──
def gen_glass_5():
    dur = 0.45
    length = int(SR * dur)
    t = np.arange(length) / SR
    # Musical overtones — pentatonic-ish partials
    freqs = [1800, 2700, 3200, 4800, 5400]
    ring = sum(np.sin(2 * np.pi * f * t + np.random.uniform(0, 2*np.pi)) *
               np.exp(-t / np.random.uniform(0.08, 0.18)) * np.random.uniform(0.2, 0.5)
               for f in freqs)
    impact = noise_burst(dur, bandwidth=(2000, 8000), decay_time=0.018)
    shards = grain_cloud(35, dur, freq_range=(2500, 7000), decay_range=(0.05, 0.15),
                         amp_range=(0.2, 0.6))
    sig = impact * 0.7 + ring * 1.0 + shards * 0.6
    sig = highshelf_rolloff(sig, cutoff=7000)
    sig = simple_reverb(sig, delays_ms=[19, 37, 59], decays=[0.28, 0.18, 0.1])
    return fade(normalize(sig), fade_in_ms=3, fade_out_ms=35)


# ── Generate all ──
if __name__ == '__main__':
    import os
    outdir = os.path.dirname(os.path.abspath(__file__))

    np.random.seed(42)

    manifest = [
        {"file": "glass_1.wav", "name": "薄玻璃", "desc": "轻薄明亮，像一片窗玻璃轻轻碎开，碎片细小、尾巴短"},
        {"file": "glass_2.wav", "name": "水晶",   "desc": "带明显音高共鸣的碎裂，清脆通透，碎片散落有余韵"},
        {"file": "glass_3.wav", "name": "冰晶",   "desc": "极高频的密集碎裂，像一把冰渣飞溅，起音锐利、衰减极快"},
        {"file": "glass_4.wav", "name": "碎镜",   "desc": "厚重的碎裂，有低频撞击底座，大片碎镜落地，戏剧感强"},
        {"file": "glass_5.wav", "name": "琉璃",   "desc": "温暖有乐感的碎裂，泛音像五声音阶，碎片声柔美不刺耳"},
    ]

    generators = [gen_glass_1, gen_glass_2, gen_glass_3, gen_glass_4, gen_glass_5]

    for info, gen in zip(manifest, generators):
        sig = gen()
        path = os.path.join(outdir, info["file"])
        wavfile.write(path, SR, to_16bit(sig))
        print(f"wrote {info['file']}")

    # Write manifest
    mpath = os.path.join(outdir, "manifest_glass.json")
    with open(mpath, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"wrote manifest_glass.json")

    # ── Self-check ──
    print("\n=== Self-check ===")
    all_ok = True
    for info in manifest:
        path = os.path.join(outdir, info["file"])
        sr, data = wavfile.read(path)
        dur = len(data) / sr
        peak = np.max(np.abs(data.astype(np.float64))) / 32767
        peak_dbfs = 20 * np.log10(peak) if peak > 0 else -999
        channels = 1 if data.ndim == 1 else data.shape[1]
        ok_dur = 0.25 <= dur <= 0.65
        ok_peak = -1.5 < peak_dbfs <= 0  # should be ~-1 dBFS
        ok_sr = sr == 44100
        ok_ch = channels == 1
        ok_bit = data.dtype == np.int16
        status = "OK" if (ok_dur and ok_peak and ok_sr and ok_ch and ok_bit) else "FAIL"
        if status == "FAIL":
            all_ok = False
        print(f"{info['file']:14s}  {info['name']}  dur={dur:.3f}s  peak={peak_dbfs:.1f}dBFS  sr={sr}  ch={channels}  16bit={ok_bit}  [{status}]")

    if all_ok:
        print("\nAll 5 files pass.")
    else:
        print("\nSOME FILES FAILED CHECK.")
