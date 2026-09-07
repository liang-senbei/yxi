#!/usr/bin/env python3
"""Generate 5 magic/soft shatter SFX for Yxi rhythm game.

Family D: 光/魔法/柔和 — notes shatter into light dust, stardust, wind chimes,
cherry blossoms, fireflies. Softer than glass, anime-like, but with a clear
"shatter" instant.

Run: python3 gen_magic.py
Output: magic_1.wav … magic_5.wav + manifest_magic.json
"""

import json, struct, wave
import numpy as np
from scipy.signal import butter, sosfilt

SR = 44100
PEAK_DBFS = -1.0  # target peak
FADE_IN_MS = 3    # click-free attack

# ── helpers ──────────────────────────────────────────────────────────

def fade_in(n_samples, ms=FADE_IN_MS):
    n = min(int(SR * ms / 1000), n_samples)
    env = np.ones(n_samples)
    env[:n] = np.linspace(0, 1, n)
    return env

def fade_out(n_samples, ms=60):
    n = min(int(SR * ms / 1000), n_samples)
    env = np.ones(n_samples)
    env[-n:] = np.linspace(1, 0, n)
    return env

def envelope(n_samples, attack_ms=FADE_IN_MS, decay_ms=60):
    return fade_in(n_samples, attack_ms) * fade_out(n_samples, decay_ms)

def exp_decay(n_samples, tau_ms):
    t = np.arange(n_samples) / SR
    return np.exp(-t / (tau_ms / 1000))

def normalize(sig, target_dbfs=PEAK_DBFS):
    peak = np.max(np.abs(sig))
    if peak < 1e-10:
        return sig
    target = 10 ** (target_dbfs / 20)
    return sig * (target / peak)

def lowpass(sig, cutoff, order=2):
    sos = butter(order, cutoff, btype='low', fs=SR, output='sos')
    return sosfilt(sos, sig)

def highpass(sig, cutoff, order=2):
    sos = butter(order, cutoff, btype='high', fs=SR, output='sos')
    return sosfilt(sos, sig)

def write_wav(path, sig):
    sig = normalize(sig)
    # ensure tail is zero
    tail = min(64, len(sig))
    sig[-tail:] *= np.linspace(1, 0, tail)
    pcm = np.clip(sig * 32767, -32768, 32767).astype(np.int16)
    with wave.open(path, 'w') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())

def pentatonic_freqs(root=880, count=8):
    """C pentatonic intervals in semitones: 0,2,4,7,9 across octaves."""
    intervals = [0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28]
    freqs = [root * 2 ** (i / 12) for i in intervals]
    rng = np.random.default_rng()
    rng.shuffle(freqs)
    return freqs[:count]

def major_freqs(root=880, count=8):
    intervals = [0, 4, 7, 12, 16, 19, 24, 28]
    freqs = [root * 2 ** (i / 12) for i in intervals]
    rng = np.random.default_rng()
    rng.shuffle(freqs)
    return freqs[:count]

def triangle_wave(freq, n_samples):
    t = np.arange(n_samples) / SR
    return 2 * np.abs(2 * (t * freq - np.floor(t * freq + 0.5))) - 1

def soft_transient(dur_ms=15, color='bright'):
    """Short filtered noise burst — the 'shatter' instant."""
    n = int(SR * dur_ms / 1000)
    rng = np.random.default_rng()
    noise = rng.normal(0, 1, n)
    if color == 'bright':
        noise = lowpass(noise, 8000)
        noise = highpass(noise, 2000)
    elif color == 'soft':
        noise = lowpass(noise, 5000)
        noise = highpass(noise, 1000)
    elif color == 'chime':
        noise = lowpass(noise, 6000)
        noise = highpass(noise, 3000)
    env = np.linspace(0, 1, min(n, int(SR * 0.002))) # 2ms attack
    env = np.concatenate([env, np.ones(max(0, n - len(env)))])
    env *= exp_decay(n, tau_ms=8)
    return noise * env

def sparkle_particles(freqs, dur_s=0.4, wave_fn='sine', scatter_ms=30):
    """Multiple pitched grains with random onset offsets → sparkle."""
    n_total = int(SR * dur_s)
    out = np.zeros(n_total)
    rng = np.random.default_rng()
    for f in freqs:
        grain_dur = rng.uniform(0.06, 0.25)
        grain_n = min(int(SR * grain_dur), n_total)
        offset = int(rng.uniform(0, scatter_ms / 1000) * SR)
        offset = min(offset, n_total - grain_n)
        t = np.arange(grain_n) / SR
        if wave_fn == 'sine':
            grain = np.sin(2 * np.pi * f * t)
        elif wave_fn == 'triangle':
            grain = triangle_wave(f, grain_n)
        else:
            grain = np.sin(2 * np.pi * f * t)
        # random amplitude
        amp = rng.uniform(0.3, 1.0)
        tau = rng.uniform(40, 150)
        grain *= amp * exp_decay(grain_n, tau) * envelope(grain_n, decay_ms=int(grain_dur * 800))
        out[offset:offset + grain_n] += grain
    return out

def air_tail(dur_s=0.3, cutoff=4000):
    """Very light filtered noise tail for air/shimmer."""
    n = int(SR * dur_s)
    rng = np.random.default_rng()
    noise = rng.normal(0, 1, n)
    noise = lowpass(noise, cutoff)
    noise = highpass(noise, 1500)
    return noise * exp_decay(n, tau_ms=120) * 0.15

# ── 5 variants ───────────────────────────────────────────────────────

def gen_magic_1():
    """光尘 — bright pentatonic sine sparkle, soft pop transient."""
    np.random.seed(42)
    dur = 0.45
    n = int(SR * dur)
    trans = soft_transient(12, 'bright')
    freqs = pentatonic_freqs(880, 7)
    particles = sparkle_particles(freqs, dur - 0.01, 'sine', scatter_ms=25)
    tail = air_tail(dur - 0.05, 5000)
    out = np.zeros(n)
    out[:len(trans)] += trans * 0.6
    out[:len(particles)] += particles
    out[:len(tail)] += tail
    out *= envelope(n, decay_ms=80)
    return out

def gen_magic_2():
    """星屑 — higher register, triangle wave grains, wider scatter."""
    np.random.seed(101)
    dur = 0.5
    n = int(SR * dur)
    trans = soft_transient(10, 'chime')
    freqs = pentatonic_freqs(1320, 9)
    particles = sparkle_particles(freqs, dur - 0.02, 'triangle', scatter_ms=40)
    tail = air_tail(dur - 0.08, 3500)
    out = np.zeros(n)
    out[:len(trans)] += trans * 0.5
    out[:len(particles)] += particles
    out[:len(tail)] += tail * 0.8
    out *= envelope(n, decay_ms=100)
    return out

def gen_magic_3():
    """风铃散 — chime-like, few low+high grains, longer ring."""
    np.random.seed(77)
    dur = 0.55
    n = int(SR * dur)
    trans = soft_transient(8, 'soft')
    # mix of low and high — wind chime set
    lo = [523.25, 659.25, 783.99]   # C5 E5 G5
    hi = [1567.98, 2093.0, 2637.02] # G6 C7 E7
    freqs = lo + hi
    particles = sparkle_particles(freqs, dur - 0.02, 'sine', scatter_ms=50)
    # extra ring on a couple tones
    t = np.arange(n) / SR
    ring = 0.3 * np.sin(2 * np.pi * 1318.5 * t) * exp_decay(n, 180)
    ring += 0.2 * np.sin(2 * np.pi * 1975.5 * t) * exp_decay(n, 140)
    tail = air_tail(dur - 0.1, 3000)
    out = np.zeros(n)
    out[:len(trans)] += trans * 0.4
    out[:len(particles)] += particles
    out += ring * envelope(n, decay_ms=120)
    out[:len(tail)] += tail * 0.6
    out *= envelope(n, decay_ms=100)
    return out

def gen_magic_4():
    """樱瓣 — softest transient, slow scatter, warm low-mid tones, gentle fade."""
    np.random.seed(210)
    dur = 0.6
    n = int(SR * dur)
    trans = soft_transient(18, 'soft')
    # warm pentatonic from A4
    freqs = pentatonic_freqs(440, 6)
    particles = sparkle_particles(freqs, dur - 0.03, 'sine', scatter_ms=60)
    # add a gentle sweep (pitch glide down)
    t = np.arange(n) / SR
    sweep_f = 2000 * np.exp(-t * 4)
    sweep = 0.15 * np.sin(2 * np.pi * np.cumsum(sweep_f) / SR) * exp_decay(n, 200)
    tail = air_tail(dur - 0.1, 2500)
    out = np.zeros(n)
    out[:len(trans)] += trans * 0.35
    out[:len(particles)] += particles
    out += sweep * envelope(n, decay_ms=150)
    out[:len(tail)] += tail * 0.5
    out *= envelope(n, decay_ms=120)
    return out

def gen_magic_5():
    """流萤 — fast bright attack, rapid high pings that scatter outward, sparkle tail."""
    np.random.seed(333)
    dur = 0.4
    n = int(SR * dur)
    trans = soft_transient(6, 'bright')
    # very high pings
    freqs = major_freqs(1760, 8)
    particles = sparkle_particles(freqs, dur - 0.01, 'sine', scatter_ms=20)
    # extra fast pings
    rng = np.random.default_rng(333)
    for _ in range(5):
        f = rng.uniform(3000, 5000)
        grain_n = int(SR * rng.uniform(0.02, 0.06))
        offset = int(rng.uniform(0, 0.03) * SR)
        t_g = np.arange(grain_n) / SR
        ping = 0.25 * np.sin(2 * np.pi * f * t_g) * exp_decay(grain_n, 25)
        end = min(offset + grain_n, n)
        particles[offset:end] += ping[:end - offset]
    tail = air_tail(dur - 0.05, 5500)
    out = np.zeros(n)
    out[:len(trans)] += trans * 0.7
    out[:len(particles)] += particles
    out[:len(tail)] += tail
    out *= envelope(n, decay_ms=70)
    # gentle high-frequency rolloff
    out = lowpass(out, 12000, order=1)
    return out

# ── main ─────────────────────────────────────────────────────────────

VARIANTS = [
    (gen_magic_1, "magic_1.wav", "光尘", "明亮五声音阶正弦粒子散开，如碎成金色光尘"),
    (gen_magic_2, "magic_2.wav", "星屑", "高音区三角波颗粒宽散射，银白星屑飘落感"),
    (gen_magic_3, "magic_3.wav", "风铃散", "低高混合长余响，像一串风铃被轻轻打碎"),
    (gen_magic_4, "magic_4.wav", "樱瓣", "最柔的瞬态，暖中低频缓散，樱花瓣飘落"),
    (gen_magic_5, "magic_5.wav", "流萤", "快速明亮起音加高频闪烁尾巴，萤火虫四散"),
]

if __name__ == '__main__':
    import os
    out_dir = os.path.dirname(os.path.abspath(__file__))

    manifest = []
    for fn, filename, name, desc in VARIANTS:
        path = os.path.join(out_dir, filename)
        sig = fn()
        write_wav(path, sig)
        manifest.append({"file": filename, "name": name, "desc": desc})
        print(f"  wrote {filename} ({name})")

    # manifest
    mpath = os.path.join(out_dir, "manifest_magic.json")
    with open(mpath, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"  wrote manifest_magic.json")

    # ── self-check ───────────────────────────────────────────────────
    print("\n=== Self-check ===")
    all_ok = True
    for _, filename, name, _ in VARIANTS:
        path = os.path.join(out_dir, filename)
        with wave.open(path, 'r') as w:
            ch = w.getnchannels()
            sw = w.getsampwidth()
            rate = w.getframerate()
            frames = w.getnframes()
            raw = w.readframes(frames)
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768
        dur = frames / rate
        peak_dbfs = 20 * np.log10(np.max(np.abs(samples)) + 1e-12)

        ok_dur = 0.25 <= dur <= 0.65
        ok_peak = -2.0 <= peak_dbfs <= 0.0
        ok_rate = rate == 44100
        ok_ch = ch == 1
        ok_bits = sw == 2
        status = "OK" if all([ok_dur, ok_peak, ok_rate, ok_ch, ok_bits]) else "FAIL"
        if status == "FAIL":
            all_ok = False
        print(f"  {filename:14s} {name:4s}  dur={dur:.3f}s  peak={peak_dbfs:+.1f}dBFS  "
              f"sr={rate}  ch={ch}  bits={sw*8}  [{status}]")

    print(f"\n{'All checks passed.' if all_ok else 'SOME CHECKS FAILED!'}")
