#!/usr/bin/env python3
"""Generate 5 digital/electronic shatter SFX for Yxi rhythm game.

Family C: digital — pixel disintegration, data fragments, glitch, synth zap, 8-bit shatter.
Each WAV: 44100 Hz, mono, 16-bit PCM, 0.25-0.65s, peak -1 dBFS, fade-in 2-5ms, fade-out to 0.
"""

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter
import json, os, struct

SR = 44100
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# ── helpers ──────────────────────────────────────────────────────────

def fade(sig, fade_in_ms=3, fade_out_ms=40):
    fi = int(SR * fade_in_ms / 1000)
    fo = int(SR * fade_out_ms / 1000)
    sig[:fi] *= np.linspace(0, 1, fi)
    sig[-fo:] *= np.linspace(1, 0, fo)
    return sig

def env_exp(n, attack_ms=1, decay_factor=6.0):
    """Exponential decay envelope with short attack."""
    t = np.arange(n) / SR
    att = int(SR * attack_ms / 1000)
    e = np.exp(-decay_factor * t)
    if att > 0 and att < n:
        e[:att] *= np.linspace(0, 1, att)
    return e

def normalize_dbfs(sig, target_db=-1.0):
    peak = np.max(np.abs(sig))
    if peak < 1e-10:
        return sig
    target_lin = 10 ** (target_db / 20.0)
    return sig * (target_lin / peak)

def lowpass(sig, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), btype='low')
    return lfilter(b, a, sig)

def highpass(sig, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), btype='high')
    return lfilter(b, a, sig)

def bitcrush(sig, bits=8, downsample=4):
    """Reduce bit depth and sample rate for lo-fi crunch."""
    levels = 2 ** bits
    crushed = np.round(sig * levels / 2) / (levels / 2)
    if downsample > 1:
        crushed[::downsample] = crushed[::downsample]  # keep these
        for i in range(len(crushed)):
            crushed[i] = crushed[i - i % downsample]
    return crushed

def square_wave(freq, duration, sr=SR):
    t = np.arange(int(sr * duration)) / sr
    return np.sign(np.sin(2 * np.pi * freq * t))

def saw_wave(freq, duration, sr=SR):
    t = np.arange(int(sr * duration)) / sr
    return 2.0 * (t * freq - np.floor(t * freq + 0.5))

def save_wav(name, sig):
    sig = normalize_dbfs(sig, -1.0)
    sig16 = np.clip(sig * 32767, -32768, 32767).astype(np.int16)
    path = os.path.join(OUT_DIR, name)
    wavfile.write(path, SR, sig16)
    return path

def rand_particles(n_samples, n_particles, freq_range, decay_range, wave='square'):
    """Generate random decaying particle pings — the 'scatter' layer."""
    out = np.zeros(n_samples)
    rng = np.random.default_rng(42)
    for _ in range(n_particles):
        freq = rng.uniform(*freq_range)
        dur_s = rng.uniform(*decay_range)
        start = rng.integers(0, max(1, n_samples // 3))
        plen = min(int(dur_s * SR), n_samples - start)
        if plen < 10:
            continue
        t = np.arange(plen) / SR
        if wave == 'square':
            osc = np.sign(np.sin(2 * np.pi * freq * t))
        elif wave == 'saw':
            osc = 2.0 * (t * freq - np.floor(t * freq + 0.5))
        else:
            osc = np.sin(2 * np.pi * freq * t)
        amp = rng.uniform(0.2, 1.0)
        e = np.exp(-rng.uniform(4, 12) * t) * amp
        out[start:start + plen] += osc * e
    return out


# ── sound 1: 像素崩 (Pixel Collapse) ────────────────────────────────
# Short square-wave impact + bitcrushed particle scatter

def gen_digital_1():
    dur = 0.35
    n = int(SR * dur)
    
    # Impact: fast descending square chirp
    t = np.arange(n) / SR
    freq_sweep = 800 * np.exp(-15 * t) + 100
    impact = np.sign(np.sin(2 * np.pi * np.cumsum(freq_sweep) / SR))
    impact *= env_exp(n, attack_ms=2, decay_factor=12)
    
    # Particles: bitcrushed square pings
    particles = rand_particles(n, 25, (200, 2000), (0.02, 0.12), wave='square')
    particles = bitcrush(particles, bits=6, downsample=3)
    particles *= env_exp(n, decay_factor=5)
    
    mix = impact * 0.6 + particles * 0.5
    mix = lowpass(mix, 7000)
    return fade(mix, fade_in_ms=2, fade_out_ms=50)


# ── sound 2: 数据尘 (Data Dust) ─────────────────────────────────────
# Granular noise burst with sample-rate folding, softer/dusty

def gen_digital_2():
    dur = 0.45
    n = int(SR * dur)
    rng = np.random.default_rng(123)
    
    # Grainy noise burst — aliased sine clusters
    t = np.arange(n) / SR
    grains = np.zeros(n)
    for i in range(40):
        f = rng.uniform(300, 4000)
        start = rng.integers(0, n // 4)
        glen = min(rng.integers(100, 800), n - start)
        g = np.sin(2 * np.pi * f * np.arange(glen) / SR)
        g *= np.hanning(glen) * rng.uniform(0.3, 1.0)
        grains[start:start + glen] += g
    
    # Downsample fold for aliasing crunch
    grains = bitcrush(grains, bits=10, downsample=6)
    
    # Soft body: filtered noise puff
    body = rng.normal(0, 1, n)
    body = lowpass(body, 3000)
    body *= env_exp(n, attack_ms=2, decay_factor=8)
    
    # Gentle high shimmer
    shimmer = np.sin(2 * np.pi * 5500 * t) * env_exp(n, decay_factor=18) * 0.15
    
    mix = body * 0.4 + grains * 0.5 + shimmer
    mix *= env_exp(n, decay_factor=4)
    mix = lowpass(mix, 6500)
    return fade(mix, fade_in_ms=3, fade_out_ms=60)


# ── sound 3: 故障片 (Glitch Shard) ──────────────────────────────────
# Harsh glitch stutter + rapid pitch-shifted repeats

def gen_digital_3():
    dur = 0.40
    n = int(SR * dur)
    rng = np.random.default_rng(77)
    
    # Core: short aggressive saw burst
    t_core = np.arange(int(SR * 0.04)) / SR
    core = saw_wave(400, 0.04) * env_exp(len(t_core), attack_ms=1, decay_factor=30)
    
    # Stutter: repeat core with pitch shifts and gaps
    stutter = np.zeros(n)
    pos = 0
    for i in range(8):
        if pos >= n:
            break
        rate = 1.0 + i * 0.15  # pitch up each repeat
        indices = np.arange(len(core)) * rate
        indices = indices[indices < len(core)].astype(int)
        chunk = core[indices] * (0.9 ** i)
        end = min(pos + len(chunk), n)
        stutter[pos:end] += chunk[:end - pos]
        gap = rng.integers(400, 1200)
        pos += len(chunk) + gap
    
    # Glitch noise bursts
    glitches = np.zeros(n)
    for _ in range(12):
        start = rng.integers(0, n * 2 // 3)
        glen = rng.integers(50, 300)
        end = min(start + glen, n)
        g = rng.choice([-1.0, 1.0], end - start)  # binary noise
        g *= env_exp(end - start, decay_factor=20) * rng.uniform(0.3, 0.8)
        glitches[start:end] += g
    
    # Descending digital tone tail
    t = np.arange(n) / SR
    tail_freq = 600 * np.exp(-8 * t) + 80
    tail = np.sign(np.sin(2 * np.pi * np.cumsum(tail_freq) / SR))
    tail *= env_exp(n, decay_factor=7) * 0.3
    
    mix = stutter * 0.5 + glitches * 0.4 + tail * 0.35
    mix = lowpass(mix, 7500)
    return fade(mix, fade_in_ms=2, fade_out_ms=45)


# ── sound 4: 电弧碎 (Arc Fracture) ──────────────────────────────────
# Synth zap impact + crackling electric scatter

def gen_digital_4():
    dur = 0.38
    n = int(SR * dur)
    rng = np.random.default_rng(999)
    t = np.arange(n) / SR
    
    # Zap: fast frequency-modulated saw
    mod = 200 * np.sin(2 * np.pi * 60 * t) * np.exp(-10 * t)
    freq = 700 + mod + 300 * np.exp(-20 * t)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    zap = saw_wave(1, 1)[:1]  # dummy, we build manually
    zap = 2.0 * (phase / (2 * np.pi) - np.floor(phase / (2 * np.pi) + 0.5))
    zap *= env_exp(n, attack_ms=1, decay_factor=10)
    
    # Crackle: sparse random impulses with ringing
    crackle = np.zeros(n)
    for _ in range(30):
        pos = rng.integers(0, n - 200)
        freq_c = rng.uniform(1000, 5000)
        ring_len = rng.integers(40, 200)
        tc = np.arange(ring_len) / SR
        ring = np.sin(2 * np.pi * freq_c * tc) * np.exp(-rng.uniform(15, 40) * tc)
        ring *= rng.uniform(0.2, 0.8)
        end = min(pos + ring_len, n)
        crackle[pos:end] += ring[:end - pos]
    
    crackle *= env_exp(n, decay_factor=5)
    
    # Sub thump
    sub = np.sin(2 * np.pi * 80 * t) * env_exp(n, decay_factor=15) * 0.3
    
    mix = zap * 0.5 + crackle * 0.45 + sub
    mix = lowpass(mix, 7200)
    mix = highpass(mix, 60)
    return fade(mix, fade_in_ms=2, fade_out_ms=40)


# ── sound 5: 方波散 (Square Scatter) ─────────────────────────────────
# Classic 8-bit shatter: arpeggio burst dissolving into square particles

def gen_digital_5():
    dur = 0.50
    n = int(SR * dur)
    rng = np.random.default_rng(2024)
    t = np.arange(n) / SR
    
    # Fast arpeggio burst (8-bit style)
    arp_notes = [523, 659, 784, 1047, 784, 659, 523, 440, 349]  # C5-E5-G5-C6 down
    note_dur = int(SR * 0.018)  # ~18ms per note
    arp = np.zeros(n)
    for i, freq in enumerate(arp_notes):
        start = i * note_dur
        end = min(start + note_dur, n)
        length = end - start
        arp[start:end] = np.sign(np.sin(2 * np.pi * freq * np.arange(length) / SR))
    arp *= env_exp(n, attack_ms=1, decay_factor=8) * 0.6
    
    # Square-wave particles scattering
    particles = rand_particles(n, 35, (150, 3000), (0.015, 0.10), wave='square')
    particles = bitcrush(particles, bits=8, downsample=2)
    particles *= env_exp(n, decay_factor=4)
    
    # Descending pitch-bend tail
    bend_freq = 300 * np.exp(-6 * t) + 60
    bend = np.sign(np.sin(2 * np.pi * np.cumsum(bend_freq) / SR))
    bend *= env_exp(n, decay_factor=6) * 0.25
    
    mix = arp * 0.5 + particles * 0.5 + bend * 0.3
    mix = lowpass(mix, 6800)
    return fade(mix, fade_in_ms=3, fade_out_ms=70)


# ── generate all ─────────────────────────────────────────────────────

SOUNDS = [
    ("digital_1.wav", "像素崩", "方波下扫冲击 + 低比特粒子崩散，干脆的像素碎裂感", gen_digital_1),
    ("digital_2.wav", "数据尘", "颗粒噪声簇 + 采样率折叠，柔和的数据粉尘消散", gen_digital_2),
    ("digital_3.wav", "故障片", "锯齿波断奏 + 音高递升重复 + 二值噪声，急促的故障碎裂", gen_digital_3),
    ("digital_4.wav", "电弧碎", "FM 锯齿 zap + 稀疏共振噼啪，电弧击碎玻璃的质感", gen_digital_4),
    ("digital_5.wav", "方波散", "8-bit 快速琶音爆发 + 方波粒子扩散，经典像素散落", gen_digital_5),
]

if __name__ == "__main__":
    manifest = []
    for fname, name, desc, gen_fn in SOUNDS:
        sig = gen_fn()
        save_wav(fname, sig)
        manifest.append({"file": fname, "name": name, "desc": desc})
        print(f"  {fname}: {len(sig)/SR:.3f}s")
    
    # Write manifest
    mpath = os.path.join(OUT_DIR, "manifest_digital.json")
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"\nManifest: {mpath}")
    
    # ── self-check ───────────────────────────────────────────────────
    print("\n=== Self-check ===")
    all_ok = True
    for fname, name, desc, _ in SOUNDS:
        path = os.path.join(OUT_DIR, fname)
        sr, data = wavfile.read(path)
        if data.dtype != np.int16:
            print(f"  FAIL {fname}: dtype {data.dtype}, expected int16")
            all_ok = False
            continue
        duration = len(data) / sr
        peak_lin = np.max(np.abs(data)) / 32768.0
        peak_db = 20 * np.log10(peak_lin) if peak_lin > 0 else -999
        channels = 1 if data.ndim == 1 else data.shape[1]
        ok = (sr == 44100 and channels == 1 and 0.25 <= duration <= 0.65
              and -1.5 <= peak_db <= 0.0)
        status = "OK" if ok else "FAIL"
        if not ok:
            all_ok = False
        print(f"  {status} {fname} ({name}): {duration:.3f}s, {peak_db:+.1f} dBFS, {sr} Hz, {channels}ch")
    
    print(f"\nAll passed: {all_ok}")
